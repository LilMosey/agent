package com.example.kb.application.service;

import com.example.kb.application.port.ChunkContentStorage;
import com.example.kb.application.port.ConversationMessageRepository;
import com.example.kb.application.port.ConversationRepository;
import com.example.kb.application.port.ConversationRetrievalReferenceRepository;
import com.example.kb.application.port.ConversationRetrievalRepository;
import com.example.kb.application.port.DocumentChunkRepository;
import com.example.kb.application.port.EmbeddingGenerator;
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
    private static final String NO_CONTEXT_ANSWER = "知识库中没有找到与该问题相关的内容。";

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository conversationMessageRepository;
    private final ConversationRetrievalRepository conversationRetrievalRepository;
    private final ConversationRetrievalReferenceRepository conversationRetrievalReferenceRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeFileRepository knowledgeFileRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final ChunkContentStorage chunkContentStorage;
    private final RagRouter ragRouter;
    private final EmbeddingGenerator embeddingGenerator;
    private final VectorIndexSearcher vectorIndexSearcher;
    private final RagAnswerGenerator ragAnswerGenerator;
    private final int retrievalTopK;
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
            EmbeddingGenerator embeddingGenerator,
            VectorIndexSearcher vectorIndexSearcher,
            RagAnswerGenerator ragAnswerGenerator,
            int retrievalTopK,
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
        this.embeddingGenerator = embeddingGenerator;
        this.vectorIndexSearcher = vectorIndexSearcher;
        this.ragAnswerGenerator = ragAnswerGenerator;
        this.retrievalTopK = retrievalTopK;
        this.contextTopK = contextTopK;
    }

    public SendMessageResult sendMessage(Long conversationId, String content) {
        log.info("发送 RAG 会话消息入参: conversationId={}, contentLength={}", conversationId, content.length());
        validateConversation(conversationId);
        ConversationMessage userMessage = saveMessage(conversationId, MessageRole.USER, content);
        List<KnowledgeBase> knowledgeBases = knowledgeBaseRepository.findAll();
        RagRouter.RouteResult routeResult = route(content, knowledgeBases);
        RagRouter.RouteResult normalizedRouteResult = normalizeRouteResult(routeResult);
        List<ReferenceCandidate> referenceCandidates = List.of();
        String answerContent;
        if (normalizedRouteResult.action() == RagRouterAction.NO_KB) {
            log.info("发送 RAG 会话消息分支: NO_KB, conversationId={}", conversationId);
            answerContent = generateAnswer(content, List.of());
        } else {
            log.info("发送 RAG 会话消息分支: SEARCH_KB, conversationId={}, knowledgeBaseIds={}",
                    conversationId, normalizedRouteResult.knowledgeBaseIds());
            referenceCandidates = searchReferences(content, normalizedRouteResult.knowledgeBaseIds());
            if (referenceCandidates.isEmpty()) {
                log.warn("发送 RAG 会话消息分支: 未检索到引用, conversationId={}", conversationId);
                answerContent = NO_CONTEXT_ANSWER;
            } else {
                answerContent = generateAnswer(content, toReferenceContexts(referenceCandidates));
            }
        }
        ConversationMessage assistantMessage = saveMessage(conversationId, MessageRole.ASSISTANT, answerContent);
        ConversationRetrieval retrieval = saveRetrieval(conversationId, assistantMessage.id(), content, normalizedRouteResult);
        List<ReferenceResult> references = saveReferences(retrieval.id(), referenceCandidates);
        updateConversationTitleIfNeeded(conversationId, userMessage);
        log.info("发送 RAG 会话消息出参: conversationId={}, assistantMessageId={}, referenceCount={}",
                conversationId, assistantMessage.id(), references.size());
        return new SendMessageResult(assistantMessage, normalizedRouteResult, references);
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

    private RagRouter.RouteResult route(String content, List<KnowledgeBase> knowledgeBases) {
        log.info("执行 RAG Router 入参: contentLength={}, knowledgeBaseCount={}", content.length(), knowledgeBases.size());
        List<RagRouter.KnowledgeBaseOption> options = new ArrayList<>(knowledgeBases.size());
        for (KnowledgeBase knowledgeBase : knowledgeBases) {
            options.add(new RagRouter.KnowledgeBaseOption(knowledgeBase.id(), knowledgeBase.name(), knowledgeBase.description()));
        }
        RagRouter.RouteResult routeResult = ragRouter.route(new RagRouter.RouteCommand(content, options));
        log.info("执行 RAG Router 出参: action={}, knowledgeBaseIds={}, confidence={}",
                routeResult.action(), routeResult.knowledgeBaseIds(), routeResult.confidence());
        return routeResult;
    }

    private RagRouter.RouteResult normalizeRouteResult(RagRouter.RouteResult routeResult) {
        if (routeResult.action() == RagRouterAction.REUSE_LAST_CONTEXT) {
            log.info("RAG Router 结果归一化分支: REUSE_LAST_CONTEXT 降级 SEARCH_KB");
            return new RagRouter.RouteResult(
                    RagRouterAction.SEARCH_KB,
                    routeResult.knowledgeBaseIds(),
                    routeResult.queryIntent(),
                    routeResult.confidence(),
                    routeResult.reason() + "；本期不复用上一轮上下文，已降级重新检索"
            );
        }
        return routeResult;
    }

    private List<ReferenceCandidate> searchReferences(String content, List<Long> knowledgeBaseIds) {
        log.info("搜索 RAG 引用入参: contentLength={}, knowledgeBaseIds={}, retrievalTopK={}, contextTopK={}",
                content.length(), knowledgeBaseIds, retrievalTopK, contextTopK);
        if (knowledgeBaseIds.isEmpty()) {
            log.warn("搜索 RAG 引用分支: knowledgeBaseIds 为空");
            return List.of();
        }
        EmbeddingGenerator.GenerateEmbeddingsResult embeddingsResult = embeddingGenerator.generate(
                new EmbeddingGenerator.GenerateEmbeddingsCommand(List.of(content))
        );
        List<Float> queryVector = embeddingsResult.items().get(0).vector();
        VectorIndexSearcher.SearchResult searchResult = vectorIndexSearcher.search(
                new VectorIndexSearcher.SearchCommand(knowledgeBaseIds, queryVector, retrievalTopK)
        );
        List<VectorIndexSearcher.SearchHit> deduplicatedHits = deduplicateHits(searchResult.hits());
        List<VectorIndexSearcher.SearchHit> selectedHits = deduplicatedHits.stream()
                .limit(contextTopK)
                .toList();
        List<ReferenceCandidate> referenceCandidates = hydrateReferences(selectedHits);
        log.info("搜索 RAG 引用出参: hitCount={}, deduplicatedCount={}, selectedCount={}, hydratedCount={}",
                searchResult.hits().size(), deduplicatedHits.size(), selectedHits.size(), referenceCandidates.size());
        return referenceCandidates;
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

    private String generateAnswer(String content, List<RagAnswerGenerator.ReferenceContext> references) {
        log.info("生成 RAG 回答入参: contentLength={}, referenceCount={}", content.length(), references.size());
        RagAnswerGenerator.AnswerResult answerResult = ragAnswerGenerator.generate(
                new RagAnswerGenerator.AnswerCommand(content, references)
        );
        log.info("生成 RAG 回答出参: answerLength={}, provider={}, model={}",
                answerResult.content().length(), answerResult.provider(), answerResult.model());
        return answerResult.content();
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

    public record SendMessageResult(
            ConversationMessage assistantMessage,
            RagRouter.RouteResult router,
            List<ReferenceResult> references
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
