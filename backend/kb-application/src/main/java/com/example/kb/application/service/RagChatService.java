package com.example.kb.application.service;

import com.example.kb.application.port.ChunkContentStorage;
import com.example.kb.application.port.ConversationMessageRepository;
import com.example.kb.application.port.ConversationRepository;
import com.example.kb.application.port.ConversationRetrievalReferenceRepository;
import com.example.kb.application.port.ConversationRetrievalRepository;
import com.example.kb.application.port.DocumentChunkRepository;
import com.example.kb.application.port.KnowledgeBaseRepository;
import com.example.kb.application.port.KnowledgeFileRepository;
import com.example.kb.application.port.RagAnswerGenerator;
import com.example.kb.application.port.RagRouter;
import com.example.kb.application.port.RerankGenerator;
import com.example.kb.application.port.VectorIndexSearcher;
import com.example.kb.domain.model.ConversationMessage;
import com.example.kb.domain.model.ConversationRetrieval;
import com.example.kb.domain.model.ConversationRetrievalReference;
import com.example.kb.domain.model.DocumentChunk;
import com.example.kb.domain.model.KnowledgeBase;
import com.example.kb.domain.model.KnowledgeFile;
import com.example.kb.domain.model.MessageRole;
import com.example.kb.domain.model.QueryIntent;
import com.example.kb.domain.model.RagRouterAction;
import com.example.kb.domain.model.RetrievalTaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);
    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository conversationMessageRepository;
    private final ConversationRetrievalRepository conversationRetrievalRepository;
    private final ConversationRetrievalReferenceRepository conversationRetrievalReferenceRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeFileRepository knowledgeFileRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final ChunkContentStorage chunkContentStorage;
    private final RagRouter ragRouter;
    private final RagRetrievalService ragRetrievalService;
    private final RagConversationContextService ragConversationContextService;
    private final RerankGenerator rerankGenerator;
    private final RagRetrievalProperties ragRetrievalProperties;
    private final RagAnswerGenerator ragAnswerGenerator;
    private final int contextTopK;
    private final List<ChatActionHandler> chatActionHandlers;

    public RagChatService(
            ConversationRepository conversationRepository,
            ConversationMessageRepository conversationMessageRepository,
            ConversationRetrievalRepository conversationRetrievalRepository,
            ConversationRetrievalReferenceRepository conversationRetrievalReferenceRepository,
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeFileRepository knowledgeFileRepository,
            DocumentChunkRepository documentChunkRepository,
            ChunkContentStorage chunkContentStorage,
            RagRouter ragRouter,
            RagRetrievalService ragRetrievalService,
            RagConversationContextService ragConversationContextService,
            RerankGenerator rerankGenerator,
            RagRetrievalProperties ragRetrievalProperties,
            RagAnswerGenerator ragAnswerGenerator,
            int contextTopK
    ) {
        this.conversationRepository = conversationRepository;
        this.conversationMessageRepository = conversationMessageRepository;
        this.conversationRetrievalRepository = conversationRetrievalRepository;
        this.conversationRetrievalReferenceRepository = conversationRetrievalReferenceRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeFileRepository = knowledgeFileRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.chunkContentStorage = chunkContentStorage;
        this.ragRouter = ragRouter;
        this.ragRetrievalService = ragRetrievalService;
        this.ragConversationContextService = ragConversationContextService;
        this.rerankGenerator = rerankGenerator;
        this.ragRetrievalProperties = ragRetrievalProperties;
        this.ragAnswerGenerator = ragAnswerGenerator;
        this.contextTopK = contextTopK;
        this.chatActionHandlers = List.of(
                new NoKnowledgeChatActionHandler(),
                new SearchKnowledgeActionHandler(),
                new UsePreviousContextActionHandler(),
                new UsePreviousAndSearchActionHandler()
        );
    }

    public SendMessageResult sendMessage(Long conversationId, String content) {
        log.info("发送 RAG 会话消息入参: conversationId={}, contentLength={}", conversationId, content.length());
        ChatFlowContext context = prepareChatFlowContext(conversationId, content);
        ChatActionResult chatActionResult = executeChatAction(context);
        String answerContent = generateAnswer(content, chatActionResult.answerReferences(), context.recentMessages());
        ConversationMessage assistantMessage = saveMessage(conversationId, MessageRole.ASSISTANT, answerContent);
        ConversationRetrieval retrieval = saveRetrieval(conversationId, assistantMessage.id(), content, context.routeResult());
        List<ReferenceResult> references = saveReferences(retrieval.id(), chatActionResult.referenceCandidates());
        ragRetrievalService.saveTaskReports(retrieval.id(), chatActionResult.retrievalTaskReports());
        updateConversationTitleIfNeeded(conversationId, context.userMessage());
        log.info("发送 RAG 会话消息出参: conversationId={}, assistantMessageId={}, referenceCount={}",
                conversationId, assistantMessage.id(), references.size());
        return new SendMessageResult(assistantMessage, context.routeResult(), references);
    }

    public SendMessageResult sendMessageStream(
            Long conversationId,
            String content,
            StreamEventConsumer streamEventConsumer
    ) {
        log.info("流式发送 RAG 会话消息入参: conversationId={}, contentLength={}", conversationId, content.length());
        ChatFlowContext context = prepareChatFlowContext(conversationId, content);
        streamEventConsumer.onEvent("router", context.routeResult());
        ChatActionResult chatActionResult = executeChatAction(context);
        streamEventConsumer.onEvent("retrieval_done", new RetrievalDoneEvent(chatActionResult.referenceCandidates().size()));
        String answerContent = generateAnswerStream(content, chatActionResult.answerReferences(), context.recentMessages(), streamEventConsumer);
        ConversationMessage assistantMessage = saveMessage(conversationId, MessageRole.ASSISTANT, answerContent);
        ConversationRetrieval retrieval = saveRetrieval(conversationId, assistantMessage.id(), content, context.routeResult());
        List<ReferenceResult> references = saveReferences(retrieval.id(), chatActionResult.referenceCandidates());
        ragRetrievalService.saveTaskReports(retrieval.id(), chatActionResult.retrievalTaskReports());
        updateConversationTitleIfNeeded(conversationId, context.userMessage());
        SendMessageResult result = new SendMessageResult(assistantMessage, context.routeResult(), references);
        streamEventConsumer.onEvent("answer_done", new AnswerDoneEvent(assistantMessage.id(), answerContent));
        streamEventConsumer.onEvent("references", references);
        log.info("流式发送 RAG 会话消息出参: conversationId={}, assistantMessageId={}, referenceCount={}",
                conversationId, assistantMessage.id(), references.size());
        return result;
    }

    private ChatFlowContext prepareChatFlowContext(Long conversationId, String content) {
        log.info("准备 RAG 聊天流程上下文入参: conversationId={}, contentLength={}", conversationId, content.length());
        validateConversation(conversationId);
        ConversationMessage userMessage = saveMessage(conversationId, MessageRole.USER, content);
        List<ConversationMessage> recentMessages = ragConversationContextService.recentMessages(conversationId, userMessage.id());
        Optional<RagConversationContextService.ReusableReferenceContext> reusableReferenceContext =
                ragConversationContextService.latestReusableReferenceContext(conversationId);
        List<KnowledgeBase> knowledgeBases = knowledgeBaseRepository.findAll();
        RagRouter.RouteResult routeResult = route(content, knowledgeBases, recentMessages, reusableReferenceContext);
        RagRouter.RouteResult normalizedRouteResult = normalizeRouteResult(routeResult);
        log.info("准备 RAG 聊天流程上下文出参: conversationId={}, userMessageId={}, action={}, recentMessageCount={}, previousContextAvailable={}",
                conversationId, userMessage.id(), normalizedRouteResult.action(), recentMessages.size(),
                reusableReferenceContext.isPresent());
        return new ChatFlowContext(
                conversationId,
                content,
                userMessage,
                recentMessages,
                reusableReferenceContext,
                normalizedRouteResult
        );
    }

    private ChatActionResult executeChatAction(ChatFlowContext context) {
        log.info("执行 RAG 聊天 Action 入参: conversationId={}, action={}",
                context.conversationId(), context.routeResult().action());
        ChatActionHandler chatActionHandler = resolveActionHandler(context.routeResult().action());
        ChatActionResult result = chatActionHandler.handle(context);
        log.info("执行 RAG 聊天 Action 出参: conversationId={}, action={}, referenceCount={}, taskCount={}",
                context.conversationId(), context.routeResult().action(),
                result.referenceCandidates().size(), result.retrievalTaskReports().size());
        return result;
    }

    private ChatActionHandler resolveActionHandler(RagRouterAction action) {
        for (ChatActionHandler chatActionHandler : chatActionHandlers) {
            if (chatActionHandler.supports(action)) {
                log.info("解析 RAG 聊天 Action Handler 出参: action={}, handler={}",
                        action, chatActionHandler.getClass().getSimpleName());
                return chatActionHandler;
            }
        }
        log.error("解析 RAG 聊天 Action Handler 异常: action={}", action);
        throw new IllegalStateException("不支持的 RAG 聊天 Action: " + action);
    }

    private void validateConversation(Long conversationId) {
        log.info("校验会话入参: conversationId={}", conversationId);
        conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        log.info("校验会话出参: conversationId={}, exists=true", conversationId);
    }

    private ConversationMessage saveMessage(Long conversationId, MessageRole role, String content) {
        log.info("保存聊天消息入参: conversationId={}, role={}, contentLength={}", conversationId, role, content.length());
        Integer messageOrder = conversationMessageRepository.nextMessageOrder(conversationId);
        ConversationMessage message = new ConversationMessage(null, conversationId, role, content, messageOrder, null, null);
        ConversationMessage saved = conversationMessageRepository.save(message);
        log.info("保存聊天消息出参: id={}, conversationId={}, role={}, messageOrder={}",
                saved.id(), saved.conversationId(), saved.role(), saved.messageOrder());
        return saved;
    }

    private RagRouter.RouteResult route(
            String content,
            List<KnowledgeBase> knowledgeBases,
            List<ConversationMessage> recentMessages,
            Optional<RagConversationContextService.ReusableReferenceContext> reusableReferenceContext
    ) {
        log.info("执行 RAG Router 入参: contentLength={}, knowledgeBaseCount={}, recentMessageCount={}, previousContextAvailable={}",
                content.length(), knowledgeBases.size(), recentMessages.size(), reusableReferenceContext.isPresent());
        List<RagRouter.KnowledgeBaseOption> options = new ArrayList<>(knowledgeBases.size());
        for (KnowledgeBase knowledgeBase : knowledgeBases) {
            options.add(new RagRouter.KnowledgeBaseOption(knowledgeBase.id(), knowledgeBase.name(), knowledgeBase.description()));
        }
        RagRouter.PreviousRagContext previousRagContext = toPreviousRagContext(reusableReferenceContext);
        RagRouter.RouteResult routeResult = ragRouter.route(new RagRouter.RouteCommand(
                content,
                options,
                recentMessages,
                previousRagContext
        ));
        log.info("执行 RAG Router 出参: action={}, knowledgeBaseIds={}, searchQueryLength={}, reusePrevious={}, confidence={}",
                routeResult.action(), routeResult.knowledgeBaseIds(),
                routeResult.searchQuery() == null ? 0 : routeResult.searchQuery().length(),
                routeResult.reusePrevious(), routeResult.confidence());
        return routeResult;
    }

    private RagRouter.PreviousRagContext toPreviousRagContext(
            Optional<RagConversationContextService.ReusableReferenceContext> reusableReferenceContext
    ) {
        if (reusableReferenceContext.isEmpty()) {
            return new RagRouter.PreviousRagContext(Boolean.FALSE, "", "", List.of());
        }
        RagConversationContextService.ReusableReferenceContext context = reusableReferenceContext.get();
        List<RagRouter.PreviousReferenceContext> references = new ArrayList<>(context.references().size());
        for (RagConversationContextService.ReusableReference reference : context.references()) {
            references.add(new RagRouter.PreviousReferenceContext(
                    reference.referenceNo(),
                    reference.file().originalFilename(),
                    reference.titlePath(),
                    reference.chunkIndex(),
                    reference.content()
            ));
        }
        log.info("构造上一轮 RAG 上下文出参: retrievalId={}, referenceCount={}, sourceQuestionLength={}, sourceAnswerLength={}",
                context.sourceRetrieval().id(), references.size(),
                context.sourceQuestion() == null ? 0 : context.sourceQuestion().length(),
                context.sourceAnswer() == null ? 0 : context.sourceAnswer().length());
        return new RagRouter.PreviousRagContext(
                Boolean.TRUE,
                context.sourceQuestion(),
                context.sourceAnswer(),
                references
        );
    }

    private RagRouter.RouteResult normalizeRouteResult(RagRouter.RouteResult routeResult) {
        if (routeResult.action() == RagRouterAction.REUSE_LAST_CONTEXT) {
            log.info("RAG Router 结果归一化分支: REUSE_LAST_CONTEXT 转为 USE_PREVIOUS_CONTEXT");
            return new RagRouter.RouteResult(
                    RagRouterAction.USE_PREVIOUS_CONTEXT,
                    List.of(),
                    routeResult.queryIntent(),
                    "",
                    Boolean.TRUE,
                    "LAST_REFERENCES",
                    routeResult.confidence(),
                    routeResult.reason()
            );
        }
        if ((routeResult.action() == RagRouterAction.SEARCH_KB
                || routeResult.action() == RagRouterAction.USE_PREVIOUS_AND_SEARCH)
                && routeResult.knowledgeBaseIds().isEmpty()) {
            log.info("RAG Router 结果归一化分支: SEARCH_KB 无知识库，按普通聊天处理");
            return new RagRouter.RouteResult(
                    RagRouterAction.NO_KB,
                    List.of(),
                    routeResult.queryIntent(),
                    "",
                    Boolean.FALSE,
                    "NONE",
                    routeResult.confidence(),
                    routeResult.reason() + "；未选出知识库，按普通聊天处理"
            );
        }
        return routeResult;
    }

    private boolean isUsePreviousContext(RagRouterAction action) {
        return action == RagRouterAction.USE_PREVIOUS_CONTEXT || action == RagRouterAction.REUSE_LAST_CONTEXT;
    }

    private String searchText(RagRouter.RouteResult routeResult, String content) {
        if (routeResult.searchQuery() == null || routeResult.searchQuery().isBlank()) {
            return content;
        }
        return routeResult.searchQuery();
    }

    private List<ReferenceCandidate> combineReferenceCandidates(
            List<ReferenceCandidate> previousCandidates,
            List<ReferenceCandidate> searchCandidates
    ) {
        log.info("合并引用上下文入参: previousCount={}, searchCount={}",
                previousCandidates.size(), searchCandidates.size());
        Map<Long, ReferenceCandidate> candidateMap = new LinkedHashMap<>();
        for (ReferenceCandidate candidate : previousCandidates) {
            candidateMap.put(candidate.chunk().id(), candidate);
        }
        for (ReferenceCandidate candidate : searchCandidates) {
            if (candidateMap.containsKey(candidate.chunk().id())) {
                log.info("合并引用上下文分支: 跳过重复 chunk, chunkId={}", candidate.chunk().id());
                continue;
            }
            candidateMap.put(candidate.chunk().id(), candidate);
        }
        List<ReferenceCandidate> combinedCandidates = new ArrayList<>(candidateMap.values());
        List<ReferenceCandidate> renumberedCandidates = renumberReferenceCandidates(combinedCandidates);
        log.info("合并引用上下文出参: combinedCount={}", renumberedCandidates.size());
        return renumberedCandidates;
    }

    private List<ReferenceCandidate> renumberReferenceCandidates(List<ReferenceCandidate> candidates) {
        List<ReferenceCandidate> renumberedCandidates = new ArrayList<>(candidates.size());
        int referenceNo = 1;
        for (ReferenceCandidate candidate : candidates) {
            renumberedCandidates.add(new ReferenceCandidate(
                    referenceNo,
                    candidate.hit(),
                    candidate.chunk(),
                    candidate.file(),
                    candidate.content()
            ));
            referenceNo++;
        }
        return renumberedCandidates;
    }

    private SearchReferenceResult searchReferences(
            Long conversationId,
            ConversationMessage userMessage,
            String content,
            String searchText,
            RagRouter.RouteResult routeResult,
            List<ConversationMessage> recentMessages,
            boolean applyRerank
    ) {
        log.info("搜索 RAG 引用入参: conversationId={}, contentLength={}, searchTextLength={}, knowledgeBaseIds={}, contextTopK={}, applyRerank={}",
                conversationId, content.length(), searchText.length(), routeResult.knowledgeBaseIds(), contextTopK, applyRerank);
        if (routeResult.knowledgeBaseIds().isEmpty()) {
            log.warn("搜索 RAG 引用分支: knowledgeBaseIds 为空");
            return new SearchReferenceResult(List.of(), List.of());
        }
        RagRetrievalService.RetrievalResult retrievalResult = ragRetrievalService.retrieve(
                new RagRetrievalService.RetrievalCommand(
                        searchText,
                        routeResult.queryIntent(),
                        routeResult.knowledgeBaseIds(),
                        recentMessages
                )
        );
        List<VectorIndexSearcher.SearchHit> selectedHits = retrievalResult.fusedHits().stream()
                .limit(applyRerank ? ragRetrievalProperties.safeRerankCandidateTopK() : contextTopK)
                .toList();
        List<ReferenceCandidate> hydratedCandidates = hydrateReferences(selectedHits);
        List<RagRetrievalService.RetrievalTaskReport> taskReports = new ArrayList<>(retrievalResult.taskReports());
        List<ReferenceCandidate> referenceCandidates;
        if (applyRerank) {
            RerankReferenceResult rerankReferenceResult = rerankReferenceCandidates(searchText, hydratedCandidates);
            rerankReferenceResult.taskReport().ifPresent(taskReports::add);
            referenceCandidates = rerankReferenceResult.referenceCandidates().stream()
                    .limit(contextTopK)
                    .toList();
        } else {
            referenceCandidates = hydratedCandidates.stream()
                    .limit(contextTopK)
                    .toList();
        }
        log.info("搜索 RAG 引用出参: fusedHitCount={}, selectedCount={}, hydratedCount={}, finalCount={}, taskCount={}",
                retrievalResult.fusedHits().size(), selectedHits.size(), hydratedCandidates.size(),
                referenceCandidates.size(), taskReports.size());
        return new SearchReferenceResult(referenceCandidates, taskReports);
    }

    private List<VectorIndexSearcher.SearchHit> deduplicateHits(List<VectorIndexSearcher.SearchHit> hits) {
        log.info("检索结果去重入参: count={}", hits.size());
        Map<Long, VectorIndexSearcher.SearchHit> hitMap = new LinkedHashMap<>();
        for (VectorIndexSearcher.SearchHit hit : hits) {
            if (!hitMap.containsKey(hit.chunkId())) {
                hitMap.put(hit.chunkId(), hit);
            } else {
                log.info("检索结果去重分支: 跳过重复 chunk, chunkId={}", hit.chunkId());
            }
        }
        List<VectorIndexSearcher.SearchHit> deduplicatedHits = new ArrayList<>(hitMap.values());
        log.info("检索结果去重出参: before={}, after={}", hits.size(), deduplicatedHits.size());
        return deduplicatedHits;
    }

    private List<ReferenceCandidate> hydrateReferences(List<VectorIndexSearcher.SearchHit> hits) {
        log.info("还原引用上下文入参: hitCount={}", hits.size());
        if (hits.isEmpty()) {
            log.info("还原引用上下文分支: 空列表");
            return List.of();
        }
        List<Long> chunkIds = hits.stream().map(VectorIndexSearcher.SearchHit::chunkId).toList();
        Map<Long, DocumentChunk> chunkMap = new LinkedHashMap<>();
        for (DocumentChunk chunk : documentChunkRepository.findByIds(chunkIds)) {
            chunkMap.put(chunk.id(), chunk);
        }
        List<ReferenceCandidate> referenceCandidates = new ArrayList<>();
        int referenceNo = 1;
        for (VectorIndexSearcher.SearchHit hit : hits) {
            DocumentChunk chunk = chunkMap.get(hit.chunkId());
            if (chunk == null) {
                log.warn("还原引用上下文分支: chunk 未找到, chunkId={}", hit.chunkId());
                continue;
            }
            Optional<KnowledgeFile> fileOptional = knowledgeFileRepository.findById(chunk.fileId());
            if (fileOptional.isEmpty()) {
                log.warn("还原引用上下文分支: 文件未找到, fileId={}", chunk.fileId());
                continue;
            }
            String chunkContent = chunkContentStorage.getChunkContent(chunk.storageBucket(), chunk.storageObjectKey());
            KnowledgeFile file = fileOptional.get();
            referenceCandidates.add(new ReferenceCandidate(referenceNo, hit, chunk, file, chunkContent));
            referenceNo++;
        }
        log.info("还原引用上下文出参: hitCount={}, referenceCount={}", hits.size(), referenceCandidates.size());
        return referenceCandidates;
    }

    private RerankReferenceResult rerankReferenceCandidates(
            String query,
            List<ReferenceCandidate> candidates
    ) {
        log.info("Rerank 引用候选入参: enabled={}, queryLength={}, candidateCount={}",
                ragRetrievalProperties.isRerankEnabled(), query.length(), candidates.size());
        if (!ragRetrievalProperties.isRerankEnabled()) {
            log.info("Rerank 引用候选分支: 配置关闭，使用 RRF 排序");
            return new RerankReferenceResult(candidates, Optional.empty());
        }
        if (candidates.isEmpty()) {
            log.info("Rerank 引用候选分支: 候选为空");
            return new RerankReferenceResult(candidates, Optional.empty());
        }
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            List<RerankGenerator.RerankDocument> documents = new ArrayList<>(candidates.size());
            for (ReferenceCandidate candidate : candidates) {
                documents.add(new RerankGenerator.RerankDocument(candidate.chunk().id(), candidate.content()));
            }
            RerankGenerator.RerankResult rerankResult = rerankGenerator.rerank(
                    new RerankGenerator.RerankCommand(
                            query,
                            documents,
                            Math.min(contextTopK, candidates.size())
                    )
            );
            List<ReferenceCandidate> rerankedCandidates = applyRerankResult(candidates, rerankResult.items());
            RagRetrievalService.RetrievalTaskReport taskReport = RagRetrievalService.RetrievalTaskReport.success(
                    RetrievalTaskType.RERANK,
                    query,
                    toSearchHits(rerankedCandidates),
                    startedAt,
                    LocalDateTime.now()
            );
            log.info("Rerank 引用候选出参: inputCount={}, outputCount={}, provider={}, model={}",
                    candidates.size(), rerankedCandidates.size(), rerankResult.provider(), rerankResult.model());
            return new RerankReferenceResult(rerankedCandidates, Optional.of(taskReport));
        } catch (Exception exception) {
            log.error("Rerank 引用候选异常: queryLength={}, candidateCount={}",
                    query.length(), candidates.size(), exception);
            RagRetrievalService.RetrievalTaskReport taskReport = RagRetrievalService.RetrievalTaskReport.failed(
                    RetrievalTaskType.RERANK,
                    query,
                    exception.getMessage(),
                    startedAt,
                    LocalDateTime.now()
            );
            return new RerankReferenceResult(candidates, Optional.of(taskReport));
        }
    }

    private List<ReferenceCandidate> applyRerankResult(
            List<ReferenceCandidate> candidates,
            List<RerankGenerator.RerankItem> rerankItems
    ) {
        if (rerankItems == null || rerankItems.isEmpty()) {
            log.warn("应用 Rerank 结果分支: rerankItems 为空，使用原排序");
            return candidates;
        }
        Map<Long, ReferenceCandidate> candidateMap = new LinkedHashMap<>();
        for (ReferenceCandidate candidate : candidates) {
            candidateMap.put(candidate.chunk().id(), candidate);
        }
        List<ReferenceCandidate> rerankedCandidates = new ArrayList<>();
        for (RerankGenerator.RerankItem item : rerankItems) {
            ReferenceCandidate candidate = candidateMap.remove(item.chunkId());
            if (candidate == null) {
                log.warn("应用 Rerank 结果分支: chunkId 未命中候选, chunkId={}", item.chunkId());
                continue;
            }
            VectorIndexSearcher.SearchHit rerankHit = new VectorIndexSearcher.SearchHit(
                    candidate.hit().knowledgeBaseId(),
                    candidate.hit().fileId(),
                    candidate.hit().chunkId(),
                    candidate.hit().chunkIndex(),
                    item.score()
            );
            rerankedCandidates.add(new ReferenceCandidate(
                    item.rankNo(),
                    rerankHit,
                    candidate.chunk(),
                    candidate.file(),
                    candidate.content()
            ));
        }
        rerankedCandidates.addAll(candidateMap.values());
        return renumberReferenceCandidates(rerankedCandidates);
    }

    private List<VectorIndexSearcher.SearchHit> toSearchHits(List<ReferenceCandidate> candidates) {
        List<VectorIndexSearcher.SearchHit> hits = new ArrayList<>(candidates.size());
        for (ReferenceCandidate candidate : candidates) {
            hits.add(candidate.hit());
        }
        return hits;
    }

    private List<RagAnswerGenerator.ReferenceContext> toReferenceContexts(List<ReferenceCandidate> candidates) {
        List<RagAnswerGenerator.ReferenceContext> contexts = new ArrayList<>(candidates.size());
        for (ReferenceCandidate candidate : candidates) {
            contexts.add(new RagAnswerGenerator.ReferenceContext(
                    candidate.referenceNo(),
                    candidate.file().originalFilename(),
                    candidate.chunk().titlePath(),
                    candidate.chunk().chunkIndex(),
                    candidate.content()
            ));
        }
        return contexts;
    }

    private String generateAnswer(
            String content,
            List<RagAnswerGenerator.ReferenceContext> references,
            List<ConversationMessage> recentMessages
    ) {
        log.info("生成 RAG 回答入参: contentLength={}, referenceCount={}, recentMessageCount={}",
                content.length(), references.size(), recentMessages.size());
        RagAnswerGenerator.AnswerResult answerResult = ragAnswerGenerator.generate(
                new RagAnswerGenerator.AnswerCommand(content, references, recentMessages)
        );
        log.info("生成 RAG 回答出参: answerLength={}, provider={}, model={}",
                answerResult.content().length(), answerResult.provider(), answerResult.model());
        return answerResult.content();
    }

    private String generateAnswerStream(
            String content,
            List<RagAnswerGenerator.ReferenceContext> references,
            List<ConversationMessage> recentMessages,
            StreamEventConsumer streamEventConsumer
    ) {
        log.info("流式生成 RAG 回答入参: contentLength={}, referenceCount={}, recentMessageCount={}",
                content.length(), references.size(), recentMessages.size());
        RagAnswerGenerator.AnswerResult answerResult = ragAnswerGenerator.generateStream(
                new RagAnswerGenerator.AnswerCommand(content, references, recentMessages),
                delta -> streamEventConsumer.onEvent("answer_delta", new AnswerDeltaEvent(delta))
        );
        log.info("流式生成 RAG 回答出参: answerLength={}, provider={}, model={}",
                answerResult.content().length(), answerResult.provider(), answerResult.model());
        return answerResult.content();
    }

    private List<ReferenceCandidate> toReferenceCandidates(
            RagConversationContextService.ReusableReferenceContext reusableReferenceContext
    ) {
        log.info("复用引用转换入参: sourceRetrievalId={}, referenceCount={}",
                reusableReferenceContext.sourceRetrieval().id(), reusableReferenceContext.references().size());
        List<ReferenceCandidate> candidates = new ArrayList<>(reusableReferenceContext.references().size());
        for (RagConversationContextService.ReusableReference reusableReference : reusableReferenceContext.references()) {
            VectorIndexSearcher.SearchHit hit = new VectorIndexSearcher.SearchHit(
                    reusableReference.knowledgeBaseId(),
                    reusableReference.fileId(),
                    reusableReference.chunkId(),
                    reusableReference.chunkIndex(),
                    reusableReference.score()
            );
            candidates.add(new ReferenceCandidate(
                    reusableReference.referenceNo(),
                    hit,
                    reusableReference.chunk(),
                    reusableReference.file(),
                    reusableReference.content()
            ));
        }
        log.info("复用引用转换出参: candidateCount={}", candidates.size());
        return candidates;
    }

    private ConversationRetrieval saveRetrieval(
            Long conversationId,
            Long messageId,
            String content,
            RagRouter.RouteResult routeResult
    ) {
        log.info("保存 RAG 检索记录入参: conversationId={}, messageId={}, action={}, knowledgeBaseIds={}",
                conversationId, messageId, routeResult.action(), routeResult.knowledgeBaseIds());
        ConversationRetrieval retrieval = new ConversationRetrieval(
                null,
                conversationId,
                messageId,
                content,
                routeResult.action(),
                routeResult.knowledgeBaseIds(),
                routeResult.queryIntent() == null ? QueryIntent.FACT_QA : routeResult.queryIntent(),
                routeResult.confidence() == null ? BigDecimal.ZERO : routeResult.confidence(),
                routeResult.reason(),
                null,
                null
        );
        ConversationRetrieval saved = conversationRetrievalRepository.save(retrieval);
        log.info("保存 RAG 检索记录出参: id={}, conversationId={}, messageId={}",
                saved.id(), saved.conversationId(), saved.messageId());
        return saved;
    }

    private List<ReferenceResult> saveReferences(Long retrievalId, List<ReferenceCandidate> candidates) {
        log.info("保存 RAG 引用记录入参: retrievalId={}, candidateCount={}", retrievalId, candidates.size());
        List<ConversationRetrievalReference> references = new ArrayList<>(candidates.size());
        List<ReferenceResult> results = new ArrayList<>(candidates.size());
        LocalDateTime now = LocalDateTime.now();
        for (ReferenceCandidate candidate : candidates) {
            references.add(new ConversationRetrievalReference(
                    null,
                    retrievalId,
                    candidate.chunk().knowledgeBaseId(),
                    candidate.chunk().fileId(),
                    candidate.chunk().id(),
                    candidate.chunk().chunkIndex(),
                    candidate.chunk().titlePath(),
                    candidate.hit().score(),
                    candidate.chunk().contentPreview(),
                    now,
                    now
            ));
            results.add(new ReferenceResult(
                    candidate.referenceNo(),
                    candidate.chunk().knowledgeBaseId(),
                    candidate.chunk().fileId(),
                    candidate.file().originalFilename(),
                    candidate.chunk().id(),
                    candidate.chunk().chunkIndex(),
                    candidate.chunk().titlePath(),
                    candidate.hit().score(),
                    candidate.chunk().contentPreview()
            ));
        }
        conversationRetrievalReferenceRepository.saveBatch(references);
        log.info("保存 RAG 引用记录出参: retrievalId={}, count={}", retrievalId, results.size());
        return results;
    }

    private void updateConversationTitleIfNeeded(Long conversationId, ConversationMessage userMessage) {
        if (userMessage.messageOrder() != 1) {
            log.info("更新会话标题分支: 非首条消息，不更新, conversationId={}, messageOrder={}",
                    conversationId, userMessage.messageOrder());
            return;
        }
        String title = userMessage.content().replaceAll("\\s+", " ").trim();
        if (title.length() > 30) {
            title = title.substring(0, 30);
        }
        conversationRepository.updateTitle(conversationId, title);
        log.info("更新会话标题出参: conversationId={}, title={}", conversationId, title);
    }

    private interface ChatActionHandler {

        boolean supports(RagRouterAction action);

        ChatActionResult handle(ChatFlowContext context);
    }

    private class NoKnowledgeChatActionHandler implements ChatActionHandler {

        @Override
        public boolean supports(RagRouterAction action) {
            return action == RagRouterAction.NO_KB;
        }

        @Override
        public ChatActionResult handle(ChatFlowContext context) {
            log.info("RAG 聊天 Action 分支: NO_KB, conversationId={}", context.conversationId());
            return new ChatActionResult(List.of(), List.of(), List.of());
        }
    }

    private class SearchKnowledgeActionHandler implements ChatActionHandler {

        @Override
        public boolean supports(RagRouterAction action) {
            return action == RagRouterAction.SEARCH_KB;
        }

        @Override
        public ChatActionResult handle(ChatFlowContext context) {
            log.info("RAG 聊天 Action 分支: SEARCH_KB, conversationId={}, knowledgeBaseIds={}",
                    context.conversationId(), context.routeResult().knowledgeBaseIds());
            SearchReferenceResult searchReferenceResult = searchReferences(
                    context.conversationId(),
                    context.userMessage(),
                    context.content(),
                    searchText(context.routeResult(), context.content()),
                    context.routeResult(),
                    context.recentMessages(),
                    true
            );
            List<ReferenceCandidate> referenceCandidates = searchReferenceResult.referenceCandidates();
            if (referenceCandidates.isEmpty()) {
                log.warn("RAG 聊天 Action 分支: SEARCH_KB 未检索到引用，按普通聊天处理, conversationId={}",
                        context.conversationId());
                return new ChatActionResult(
                        referenceCandidates,
                        searchReferenceResult.taskReports(),
                        List.of()
                );
            }
            return new ChatActionResult(
                    referenceCandidates,
                    searchReferenceResult.taskReports(),
                    toReferenceContexts(referenceCandidates)
            );
        }
    }

    private class UsePreviousContextActionHandler implements ChatActionHandler {

        @Override
        public boolean supports(RagRouterAction action) {
            return isUsePreviousContext(action);
        }

        @Override
        public ChatActionResult handle(ChatFlowContext context) {
            log.info("RAG 聊天 Action 分支: USE_PREVIOUS_CONTEXT, conversationId={}", context.conversationId());
            if (context.reusableReferenceContext().isEmpty()) {
                log.warn("RAG 聊天 Action 分支: USE_PREVIOUS_CONTEXT 未找到可复用引用，按普通聊天处理, conversationId={}",
                        context.conversationId());
                return new ChatActionResult(List.of(), List.of(), List.of());
            }
            List<ReferenceCandidate> referenceCandidates = toReferenceCandidates(context.reusableReferenceContext().get());
            log.info("RAG 聊天 Action 分支: USE_PREVIOUS_CONTEXT 复用引用, conversationId={}, referenceCount={}",
                    context.conversationId(), referenceCandidates.size());
            return new ChatActionResult(
                    referenceCandidates,
                    List.of(),
                    toReferenceContexts(referenceCandidates)
            );
        }
    }

    private class UsePreviousAndSearchActionHandler implements ChatActionHandler {

        @Override
        public boolean supports(RagRouterAction action) {
            return action == RagRouterAction.USE_PREVIOUS_AND_SEARCH;
        }

        @Override
        public ChatActionResult handle(ChatFlowContext context) {
            log.info("RAG 聊天 Action 分支: USE_PREVIOUS_AND_SEARCH, conversationId={}, knowledgeBaseIds={}",
                    context.conversationId(), context.routeResult().knowledgeBaseIds());
            List<ReferenceCandidate> previousCandidates = context.reusableReferenceContext()
                    .map(RagChatService.this::toReferenceCandidates)
                    .orElse(List.of());
            SearchReferenceResult searchReferenceResult = searchReferences(
                    context.conversationId(),
                    context.userMessage(),
                    context.content(),
                    searchText(context.routeResult(), context.content()),
                    context.routeResult(),
                    context.recentMessages(),
                    false
            );
            List<ReferenceCandidate> combinedCandidates =
                    combineReferenceCandidates(previousCandidates, searchReferenceResult.referenceCandidates());
            RerankReferenceResult rerankReferenceResult = rerankReferenceCandidates(
                    searchText(context.routeResult(), context.content()),
                    combinedCandidates
            );
            List<RagRetrievalService.RetrievalTaskReport> retrievalTaskReports =
                    new ArrayList<>(searchReferenceResult.taskReports());
            rerankReferenceResult.taskReport().ifPresent(retrievalTaskReports::add);
            List<ReferenceCandidate> referenceCandidates = rerankReferenceResult.referenceCandidates().stream()
                    .limit(contextTopK)
                    .toList();
            if (referenceCandidates.isEmpty()) {
                log.warn("RAG 聊天 Action 分支: USE_PREVIOUS_AND_SEARCH 未获得引用，按普通聊天处理, conversationId={}",
                        context.conversationId());
                return new ChatActionResult(referenceCandidates, retrievalTaskReports, List.of());
            }
            log.info("RAG 聊天 Action 分支: USE_PREVIOUS_AND_SEARCH 出参, conversationId={}, previousCount={}, searchCount={}, finalCount={}",
                    context.conversationId(), previousCandidates.size(),
                    searchReferenceResult.referenceCandidates().size(), referenceCandidates.size());
            return new ChatActionResult(
                    referenceCandidates,
                    retrievalTaskReports,
                    toReferenceContexts(referenceCandidates)
            );
        }
    }

    private record ChatFlowContext(
            Long conversationId,
            String content,
            ConversationMessage userMessage,
            List<ConversationMessage> recentMessages,
            Optional<RagConversationContextService.ReusableReferenceContext> reusableReferenceContext,
            RagRouter.RouteResult routeResult
    ) {
    }

    private record ChatActionResult(
            List<ReferenceCandidate> referenceCandidates,
            List<RagRetrievalService.RetrievalTaskReport> retrievalTaskReports,
            List<RagAnswerGenerator.ReferenceContext> answerReferences
    ) {
    }

    private record ReferenceCandidate(
            Integer referenceNo,
            VectorIndexSearcher.SearchHit hit,
            DocumentChunk chunk,
            KnowledgeFile file,
            String content
    ) {
    }

    private record SearchReferenceResult(
            List<ReferenceCandidate> referenceCandidates,
            List<RagRetrievalService.RetrievalTaskReport> taskReports
    ) {
    }

    private record RerankReferenceResult(
            List<ReferenceCandidate> referenceCandidates,
            Optional<RagRetrievalService.RetrievalTaskReport> taskReport
    ) {
    }

    public record SendMessageResult(
            ConversationMessage assistantMessage,
            RagRouter.RouteResult router,
            List<ReferenceResult> references
    ) {
    }

    public interface StreamEventConsumer {

        void onEvent(String eventName, Object data);
    }

    public record RetrievalDoneEvent(
            Integer referenceCount
    ) {
    }

    public record AnswerDeltaEvent(
            String delta
    ) {
    }

    public record AnswerDoneEvent(
            Long messageId,
            String content
    ) {
    }

    public record ReferenceResult(
            Integer referenceNo,
            Long knowledgeBaseId,
            Long fileId,
            String fileName,
            Long chunkId,
            Integer chunkIndex,
            String titlePath,
            BigDecimal score,
            String contentPreview
    ) {
    }
}
