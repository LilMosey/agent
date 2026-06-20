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
    private final RagAnswerGenerator ragAnswerGenerator;
    private final int contextTopK;

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
        this.ragAnswerGenerator = ragAnswerGenerator;
        this.contextTopK = contextTopK;
    }

    public SendMessageResult sendMessage(Long conversationId, String content) {
        log.info("发送 RAG 会话消息入参: conversationId={}, contentLength={}", conversationId, content.length());
        validateConversation(conversationId);
        ConversationMessage userMessage = saveMessage(conversationId, MessageRole.USER, content);
        List<ConversationMessage> recentMessages = ragConversationContextService.recentMessages(conversationId, userMessage.id());
        Optional<RagConversationContextService.ReusableReferenceContext> reusableReferenceContext =
                ragConversationContextService.latestReusableReferenceContext(conversationId);
        List<KnowledgeBase> knowledgeBases = knowledgeBaseRepository.findAll();
        RagRouter.RouteResult routeResult = route(content, knowledgeBases, recentMessages, reusableReferenceContext);
        RagRouter.RouteResult normalizedRouteResult = normalizeRouteResult(routeResult);
        List<ReferenceCandidate> referenceCandidates = List.of();
        List<RagRetrievalService.RetrievalTaskReport> retrievalTaskReports = List.of();
        String answerContent;
        if (normalizedRouteResult.action() == RagRouterAction.NO_KB) {
            log.info("发送 RAG 会话消息分支: NO_KB, conversationId={}", conversationId);
            answerContent = generateAnswer(content, List.of(), recentMessages);
        } else if (normalizedRouteResult.action() == RagRouterAction.SEARCH_KB) {
            log.info("发送 RAG 会话消息分支: SEARCH_KB, conversationId={}, knowledgeBaseIds={}",
                    conversationId, normalizedRouteResult.knowledgeBaseIds());
            SearchReferenceResult searchReferenceResult = searchReferences(
                    conversationId,
                    userMessage,
                    content,
                    searchText(normalizedRouteResult, content),
                    normalizedRouteResult,
                    recentMessages
            );
            referenceCandidates = searchReferenceResult.referenceCandidates();
            retrievalTaskReports = searchReferenceResult.taskReports();
            if (referenceCandidates.isEmpty()) {
                log.warn("发送 RAG 会话消息分支: 未检索到引用，按普通聊天处理, conversationId={}", conversationId);
                answerContent = generateAnswer(content, List.of(), recentMessages);
            } else {
                answerContent = generateAnswer(content, toReferenceContexts(referenceCandidates), recentMessages);
            }
        } else if (isUsePreviousContext(normalizedRouteResult.action())) {
            log.info("发送 RAG 会话消息分支: USE_PREVIOUS_CONTEXT, conversationId={}", conversationId);
            if (reusableReferenceContext.isPresent()) {
                referenceCandidates = toReferenceCandidates(reusableReferenceContext.get());
                answerContent = generateAnswer(content, toReferenceContexts(referenceCandidates), recentMessages);
            } else {
                log.warn("发送 RAG 会话消息分支: USE_PREVIOUS_CONTEXT 未找到可复用引用，按普通聊天处理, conversationId={}", conversationId);
                answerContent = generateAnswer(content, List.of(), recentMessages);
            }
        } else {
            log.info("发送 RAG 会话消息分支: USE_PREVIOUS_AND_SEARCH, conversationId={}, knowledgeBaseIds={}",
                    conversationId, normalizedRouteResult.knowledgeBaseIds());
            List<ReferenceCandidate> previousCandidates = reusableReferenceContext
                    .map(this::toReferenceCandidates)
                    .orElse(List.of());
            SearchReferenceResult searchReferenceResult = searchReferences(
                    conversationId,
                    userMessage,
                    content,
                    searchText(normalizedRouteResult, content),
                    normalizedRouteResult,
                    recentMessages
            );
            retrievalTaskReports = searchReferenceResult.taskReports();
            referenceCandidates = combineReferenceCandidates(previousCandidates, searchReferenceResult.referenceCandidates());
            if (referenceCandidates.isEmpty()) {
                log.warn("发送 RAG 会话消息分支: USE_PREVIOUS_AND_SEARCH 未获得引用，按普通聊天处理, conversationId={}", conversationId);
                answerContent = generateAnswer(content, List.of(), recentMessages);
            } else {
                answerContent = generateAnswer(content, toReferenceContexts(referenceCandidates), recentMessages);
            }
        }
        ConversationMessage assistantMessage = saveMessage(conversationId, MessageRole.ASSISTANT, answerContent);
        ConversationRetrieval retrieval = saveRetrieval(conversationId, assistantMessage.id(), content, normalizedRouteResult);
        List<ReferenceResult> references = saveReferences(retrieval.id(), referenceCandidates);
        ragRetrievalService.saveTaskReports(retrieval.id(), retrievalTaskReports);
        updateConversationTitleIfNeeded(conversationId, userMessage);
        log.info("发送 RAG 会话消息出参: conversationId={}, assistantMessageId={}, referenceCount={}",
                conversationId, assistantMessage.id(), references.size());
        return new SendMessageResult(assistantMessage, normalizedRouteResult, references);
    }

    public SendMessageResult sendMessageStream(
            Long conversationId,
            String content,
            StreamEventConsumer streamEventConsumer
    ) {
        log.info("流式发送 RAG 会话消息入参: conversationId={}, contentLength={}", conversationId, content.length());
        validateConversation(conversationId);
        ConversationMessage userMessage = saveMessage(conversationId, MessageRole.USER, content);
        List<ConversationMessage> recentMessages = ragConversationContextService.recentMessages(conversationId, userMessage.id());
        Optional<RagConversationContextService.ReusableReferenceContext> reusableReferenceContext =
                ragConversationContextService.latestReusableReferenceContext(conversationId);
        List<KnowledgeBase> knowledgeBases = knowledgeBaseRepository.findAll();
        RagRouter.RouteResult routeResult = route(content, knowledgeBases, recentMessages, reusableReferenceContext);
        RagRouter.RouteResult normalizedRouteResult = normalizeRouteResult(routeResult);
        streamEventConsumer.onEvent("router", normalizedRouteResult);
        List<ReferenceCandidate> referenceCandidates = List.of();
        List<RagRetrievalService.RetrievalTaskReport> retrievalTaskReports = List.of();
        List<RagAnswerGenerator.ReferenceContext> answerReferences = List.of();
        if (normalizedRouteResult.action() == RagRouterAction.SEARCH_KB) {
            SearchReferenceResult searchReferenceResult = searchReferences(
                    conversationId,
                    userMessage,
                    content,
                    searchText(normalizedRouteResult, content),
                    normalizedRouteResult,
                    recentMessages
            );
            referenceCandidates = searchReferenceResult.referenceCandidates();
            retrievalTaskReports = searchReferenceResult.taskReports();
            answerReferences = toReferenceContexts(referenceCandidates);
        } else if (isUsePreviousContext(normalizedRouteResult.action())) {
            if (reusableReferenceContext.isPresent()) {
                referenceCandidates = toReferenceCandidates(reusableReferenceContext.get());
                answerReferences = toReferenceContexts(referenceCandidates);
                log.info("流式发送 RAG 会话消息分支: USE_PREVIOUS_CONTEXT 复用引用, conversationId={}, referenceCount={}",
                        conversationId, referenceCandidates.size());
            } else {
                log.warn("流式发送 RAG 会话消息分支: USE_PREVIOUS_CONTEXT 未找到可复用引用，按普通聊天处理, conversationId={}",
                        conversationId);
            }
        } else if (normalizedRouteResult.action() == RagRouterAction.USE_PREVIOUS_AND_SEARCH) {
            List<ReferenceCandidate> previousCandidates = reusableReferenceContext
                    .map(this::toReferenceCandidates)
                    .orElse(List.of());
            SearchReferenceResult searchReferenceResult = searchReferences(
                    conversationId,
                    userMessage,
                    content,
                    searchText(normalizedRouteResult, content),
                    normalizedRouteResult,
                    recentMessages
            );
            retrievalTaskReports = searchReferenceResult.taskReports();
            referenceCandidates = combineReferenceCandidates(previousCandidates, searchReferenceResult.referenceCandidates());
            answerReferences = toReferenceContexts(referenceCandidates);
            log.info("流式发送 RAG 会话消息分支: USE_PREVIOUS_AND_SEARCH, conversationId={}, referenceCount={}",
                    conversationId, referenceCandidates.size());
        }
        streamEventConsumer.onEvent("retrieval_done", new RetrievalDoneEvent(referenceCandidates.size()));
        String answerContent = generateAnswerStream(content, answerReferences, recentMessages, streamEventConsumer);
        ConversationMessage assistantMessage = saveMessage(conversationId, MessageRole.ASSISTANT, answerContent);
        ConversationRetrieval retrieval = saveRetrieval(conversationId, assistantMessage.id(), content, normalizedRouteResult);
        List<ReferenceResult> references = saveReferences(retrieval.id(), referenceCandidates);
        ragRetrievalService.saveTaskReports(retrieval.id(), retrievalTaskReports);
        updateConversationTitleIfNeeded(conversationId, userMessage);
        SendMessageResult result = new SendMessageResult(assistantMessage, normalizedRouteResult, references);
        streamEventConsumer.onEvent("answer_done", new AnswerDoneEvent(assistantMessage.id(), answerContent));
        streamEventConsumer.onEvent("references", references);
        log.info("流式发送 RAG 会话消息出参: conversationId={}, assistantMessageId={}, referenceCount={}",
                conversationId, assistantMessage.id(), references.size());
        return result;
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
            List<ConversationMessage> recentMessages
    ) {
        log.info("搜索 RAG 引用入参: conversationId={}, contentLength={}, searchTextLength={}, knowledgeBaseIds={}, contextTopK={}",
                conversationId, content.length(), searchText.length(), routeResult.knowledgeBaseIds(), contextTopK);
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
                .limit(contextTopK)
                .toList();
        List<ReferenceCandidate> referenceCandidates = hydrateReferences(selectedHits);
        log.info("搜索 RAG 引用出参: fusedHitCount={}, selectedCount={}, hydratedCount={}, taskCount={}",
                retrievalResult.fusedHits().size(), selectedHits.size(), referenceCandidates.size(),
                retrievalResult.taskReports().size());
        return new SearchReferenceResult(referenceCandidates, retrievalResult.taskReports());
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
