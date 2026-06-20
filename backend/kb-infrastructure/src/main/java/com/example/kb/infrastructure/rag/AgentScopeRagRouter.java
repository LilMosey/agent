package com.example.kb.infrastructure.rag;

import com.example.kb.application.port.RagRouter;
import com.example.kb.domain.model.QueryIntent;
import com.example.kb.domain.model.RagRouterAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AgentScopeRagRouter implements RagRouter {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeRagRouter.class);

    private final RagProperties properties;
    private final RagPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final OpenAIChatModel chatModel;

    public AgentScopeRagRouter(
            RagProperties properties,
            RagPromptBuilder promptBuilder,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.promptBuilder = promptBuilder;
        this.objectMapper = objectMapper;
        this.chatModel = OpenAIChatModel.builder()
                .apiKey(properties.apiKey())
                .baseUrl(properties.baseUrl())
                .modelName(properties.routerModel())
                .stream(false)
                .build();
    }

    @Override
    public RouteResult route(RouteCommand command) {
        log.info("RAG Router 入参: questionLength={}, knowledgeBaseCount={}, recentMessageCount={}, previousContextAvailable={}, provider={}, model={}",
                command.userQuestion().length(), command.knowledgeBases().size(), command.recentMessages().size(),
                command.previousRagContext() != null && Boolean.TRUE.equals(command.previousRagContext().available()),
                properties.provider(), properties.routerModel());
        if (command.knowledgeBases().isEmpty()) {
            log.warn("RAG Router 分支: 无可用知识库，返回 NO_KB");
            return new RouteResult(RagRouterAction.NO_KB, List.of(), QueryIntent.CHAT, "",
                    Boolean.FALSE, "NONE", BigDecimal.ZERO, "当前没有可用知识库");
        }
        try {
            String prompt = promptBuilder.buildRouterPrompt(
                    command.userQuestion(),
                    command.knowledgeBases(),
                    command.recentMessages(),
                    command.previousRagContext()
            );
            String responseText = callModel(prompt, properties.routerModel());
            LlmRouterResponse response = objectMapper.readValue(cleanJson(responseText), LlmRouterResponse.class);
            RouteResult routeResult = normalize(response, command);
            log.info("RAG Router 出参: action={}, knowledgeBaseIds={}, queryIntent={}, searchQueryLength={}, reusePrevious={}, confidence={}, reason={}",
                    routeResult.action(), routeResult.knowledgeBaseIds(), routeResult.queryIntent(),
                    routeResult.searchQuery() == null ? 0 : routeResult.searchQuery().length(),
                    routeResult.reusePrevious(), routeResult.confidence(), routeResult.reason());
            return routeResult;
        } catch (Exception exception) {
            log.error("RAG Router 异常: questionLength={}, knowledgeBaseCount={}",
                    command.userQuestion().length(), command.knowledgeBases().size(), exception);
            RouteResult fallback = fallbackNoKb("Router 调用失败，按普通聊天处理");
            log.warn("RAG Router 异常兜底出参: action={}, knowledgeBaseIds={}, reason={}",
                    fallback.action(), fallback.knowledgeBaseIds(), fallback.reason());
            return fallback;
        }
    }

    private RouteResult normalize(LlmRouterResponse response, RouteCommand command) {
        RagRouterAction action = parseAction(response.action());
        if (action == RagRouterAction.REUSE_LAST_CONTEXT) {
            log.info("RAG Router 分支: 兼容旧动作 REUSE_LAST_CONTEXT，归一为 USE_PREVIOUS_CONTEXT");
            action = RagRouterAction.USE_PREVIOUS_CONTEXT;
        }
        QueryIntent queryIntent = parseQueryIntent(response.queryIntent());
        BigDecimal confidence = response.confidence() == null ? BigDecimal.ZERO : response.confidence();
        String reason = response.reason() == null || response.reason().isBlank() ? "Router 未返回原因" : response.reason().trim();
        String searchQuery = response.searchQuery() == null ? "" : response.searchQuery().trim();
        Boolean reusePrevious = response.reusePrevious();
        String reusePolicy = response.reusePolicy() == null || response.reusePolicy().isBlank()
                ? "NONE"
                : response.reusePolicy().trim();
        List<Long> selectedIds = filterKnowledgeBaseIds(response.knowledgeBaseIds(), command.knowledgeBases());
        if ((action == RagRouterAction.SEARCH_KB || action == RagRouterAction.USE_PREVIOUS_AND_SEARCH)
                && selectedIds.isEmpty()) {
            log.warn("RAG Router 分支: SEARCH_KB 但未选出知识库，按普通聊天处理");
            if (action == RagRouterAction.USE_PREVIOUS_AND_SEARCH
                    && command.previousRagContext() != null
                    && Boolean.TRUE.equals(command.previousRagContext().available())) {
                action = RagRouterAction.USE_PREVIOUS_CONTEXT;
                reusePrevious = Boolean.TRUE;
                reusePolicy = "LAST_REFERENCES";
                reason = reason + "；未选出可补查知识库，系统改为复用上一轮上下文";
            } else {
                action = RagRouterAction.NO_KB;
                reusePrevious = Boolean.FALSE;
                reusePolicy = "NONE";
                reason = reason + "；未选出可查询知识库，系统按普通聊天处理";
            }
        }
        if (action == RagRouterAction.NO_KB) {
            selectedIds = List.of();
            reusePrevious = Boolean.FALSE;
            reusePolicy = "NONE";
        }
        if (action == RagRouterAction.SEARCH_KB && searchQuery.isBlank()) {
            searchQuery = command.userQuestion();
        }
        if (action == RagRouterAction.USE_PREVIOUS_CONTEXT) {
            selectedIds = List.of();
            searchQuery = "";
            reusePrevious = Boolean.TRUE;
            reusePolicy = "LAST_REFERENCES";
        }
        if (action == RagRouterAction.USE_PREVIOUS_AND_SEARCH) {
            if (searchQuery.isBlank()) {
                searchQuery = command.userQuestion();
            }
            reusePrevious = Boolean.TRUE;
            reusePolicy = "LAST_REFERENCES_PLUS_SEARCH";
        }
        return new RouteResult(action, selectedIds, queryIntent, searchQuery, reusePrevious, reusePolicy, confidence, reason);
    }

    private RagRouterAction parseAction(String action) {
        try {
            return RagRouterAction.valueOf(action);
        } catch (Exception exception) {
            log.warn("RAG Router 分支: action 非法，按普通聊天处理, action={}", action);
            return RagRouterAction.NO_KB;
        }
    }

    private QueryIntent parseQueryIntent(String queryIntent) {
        try {
            return QueryIntent.valueOf(queryIntent);
        } catch (Exception exception) {
            log.warn("RAG Router 分支: queryIntent 非法，降级 FACT_QA, queryIntent={}", queryIntent);
            return QueryIntent.FACT_QA;
        }
    }

    private List<Long> filterKnowledgeBaseIds(List<Long> knowledgeBaseIds, List<KnowledgeBaseOption> knowledgeBases) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        Set<Long> validIds = new HashSet<>(allKnowledgeBaseIds(knowledgeBases));
        List<Long> filteredIds = new ArrayList<>();
        for (Long knowledgeBaseId : knowledgeBaseIds) {
            if (validIds.contains(knowledgeBaseId) && !filteredIds.contains(knowledgeBaseId)) {
                filteredIds.add(knowledgeBaseId);
            } else {
                log.warn("RAG Router 分支: 跳过非法或重复知识库 ID, knowledgeBaseId={}", knowledgeBaseId);
            }
        }
        return filteredIds;
    }

    private List<Long> allKnowledgeBaseIds(List<KnowledgeBaseOption> knowledgeBases) {
        List<Long> ids = new ArrayList<>(knowledgeBases.size());
        for (KnowledgeBaseOption knowledgeBase : knowledgeBases) {
            ids.add(knowledgeBase.id());
        }
        return ids;
    }

    private RouteResult fallbackNoKb(String reason) {
        return new RouteResult(
                RagRouterAction.NO_KB,
                List.of(),
                QueryIntent.CHAT,
                "",
                Boolean.FALSE,
                "NONE",
                BigDecimal.ZERO,
                reason
        );
    }

    private String callModel(String prompt, String modelName) {
        Msg message = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(prompt)
                .build();
        GenerateOptions generateOptions = GenerateOptions.builder()
                .apiKey(properties.apiKey())
                .baseUrl(properties.baseUrl())
                .modelName(modelName)
                .stream(Boolean.FALSE)
                .temperature(0.0D)
                .build();
        Flux<ChatResponse> responseFlux = chatModel.stream(List.of(message), List.of(), generateOptions);
        List<ChatResponse> responses = responseFlux.collectList().block();
        return extractText(responses);
    }

    private String extractText(List<ChatResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            throw new IllegalStateException("LLM 返回为空。");
        }
        StringBuilder builder = new StringBuilder();
        for (ChatResponse response : responses) {
            if (response.getContent() == null) {
                continue;
            }
            for (ContentBlock contentBlock : response.getContent()) {
                if (contentBlock instanceof TextBlock textBlock) {
                    builder.append(textBlock.getText());
                }
            }
        }
        String text = builder.toString().trim();
        if (text.isBlank()) {
            throw new IllegalStateException("LLM 文本内容为空。");
        }
        return text;
    }

    private String cleanJson(String responseText) {
        String text = responseText.trim();
        if (text.startsWith("```json")) {
            text = text.substring("```json".length()).trim();
        } else if (text.startsWith("```")) {
            text = text.substring("```".length()).trim();
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - "```".length()).trim();
        }
        return text;
    }

    private record LlmRouterResponse(
            String action,
            List<Long> knowledgeBaseIds,
            String queryIntent,
            String searchQuery,
            Boolean reusePrevious,
            String reusePolicy,
            BigDecimal confidence,
            String reason
    ) {
    }
}
