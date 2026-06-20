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
        log.info("RAG Router 入参: questionLength={}, knowledgeBaseCount={}, provider={}, model={}",
                command.userQuestion().length(), command.knowledgeBases().size(), properties.provider(), properties.routerModel());
        if (command.knowledgeBases().isEmpty()) {
            log.warn("RAG Router 分支: 无可用知识库，返回 NO_KB");
            return new RouteResult(RagRouterAction.NO_KB, List.of(), QueryIntent.CHAT, BigDecimal.ZERO, "当前没有可用知识库");
        }
        try {
            String prompt = promptBuilder.buildRouterPrompt(command.userQuestion(), command.knowledgeBases());
            String responseText = callModel(prompt, properties.routerModel());
            LlmRouterResponse response = objectMapper.readValue(cleanJson(responseText), LlmRouterResponse.class);
            RouteResult routeResult = normalize(response, command.knowledgeBases());
            log.info("RAG Router 出参: action={}, knowledgeBaseIds={}, queryIntent={}, confidence={}, reason={}",
                    routeResult.action(), routeResult.knowledgeBaseIds(), routeResult.queryIntent(),
                    routeResult.confidence(), routeResult.reason());
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

    private RouteResult normalize(LlmRouterResponse response, List<KnowledgeBaseOption> knowledgeBases) {
        RagRouterAction action = parseAction(response.action());
        QueryIntent queryIntent = parseQueryIntent(response.queryIntent());
        BigDecimal confidence = response.confidence() == null ? BigDecimal.ZERO : response.confidence();
        String reason = response.reason() == null || response.reason().isBlank() ? "Router 未返回原因" : response.reason().trim();
        List<Long> selectedIds = filterKnowledgeBaseIds(response.knowledgeBaseIds(), knowledgeBases);
        if (action == RagRouterAction.SEARCH_KB && selectedIds.isEmpty()) {
            log.warn("RAG Router 分支: SEARCH_KB 但未选出知识库，按普通聊天处理");
            action = RagRouterAction.NO_KB;
            reason = reason + "；未选出可查询知识库，系统按普通聊天处理";
        }
        if (action == RagRouterAction.NO_KB) {
            selectedIds = List.of();
        }
        return new RouteResult(action, selectedIds, queryIntent, confidence, reason);
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
            BigDecimal confidence,
            String reason
    ) {
    }
}
