package com.example.kb.bootstrap;

import com.example.kb.application.port.ChunkContentStorage;
import com.example.kb.application.port.ChunkEnrichmentGenerator;
import com.example.kb.application.port.ChunkEnrichmentObjectStorage;
import com.example.kb.application.port.ChunkEnrichmentRepository;
import com.example.kb.application.port.ChunkObjectStorage;
import com.example.kb.application.port.ConversationMessageRepository;
import com.example.kb.application.port.ConversationRepository;
import com.example.kb.application.port.ConversationRetrievalReferenceRepository;
import com.example.kb.application.port.ConversationRetrievalRepository;
import com.example.kb.application.port.ConversationRetrievalTaskHitRepository;
import com.example.kb.application.port.ConversationRetrievalTaskRepository;
import com.example.kb.application.port.DocumentChunkRepository;
import com.example.kb.application.port.DocumentChunker;
import com.example.kb.application.port.EmbeddingGenerator;
import com.example.kb.application.port.HydeGenerator;
import com.example.kb.application.port.IndexPipeline;
import com.example.kb.application.port.KeywordIndexSearcher;
import com.example.kb.application.port.KnowledgeBaseRepository;
import com.example.kb.application.port.KnowledgeFileRepository;
import com.example.kb.application.port.KnowledgeFileIndexTaskRepository;
import com.example.kb.application.port.MultiQueryGenerator;
import com.example.kb.application.port.ObjectStorage;
import com.example.kb.application.port.QueryRewriteGenerator;
import com.example.kb.application.port.RagAnswerGenerator;
import com.example.kb.application.port.RagRouter;
import com.example.kb.application.port.VectorIndexCleaner;
import com.example.kb.application.port.VectorIndexSearcher;
import com.example.kb.application.port.VectorIndexWriter;
import com.example.kb.application.service.ChunkEnrichmentService;
import com.example.kb.application.service.ChunkEmbeddingService;
import com.example.kb.application.service.ConversationService;
import com.example.kb.application.service.KnowledgeBaseService;
import com.example.kb.application.service.DocumentChunkService;
import com.example.kb.application.service.KnowledgeFileIndexTaskService;
import com.example.kb.application.service.KnowledgeFileService;
import com.example.kb.application.service.RagChatService;
import com.example.kb.application.service.RagRetrievalProperties;
import com.example.kb.application.service.RagRetrievalService;
import com.example.kb.application.service.RrfFusionService;
import com.example.kb.infrastructure.embedding.DashScopeEmbeddingGenerator;
import com.example.kb.infrastructure.embedding.EmbeddingProperties;
import com.example.kb.infrastructure.enrichment.AgentScopeChunkEnrichmentGenerator;
import com.example.kb.infrastructure.enrichment.ChunkEnrichmentProperties;
import com.example.kb.infrastructure.enrichment.ChunkEnrichmentPromptBuilder;
import com.example.kb.infrastructure.enrichment.MockChunkEnrichmentGenerator;
import com.example.kb.infrastructure.rag.AgentScopeRagAnswerGenerator;
import com.example.kb.infrastructure.rag.AgentScopeRagRouter;
import com.example.kb.infrastructure.rag.AgentScopeHydeGenerator;
import com.example.kb.infrastructure.rag.AgentScopeMultiQueryGenerator;
import com.example.kb.infrastructure.rag.AgentScopeQueryRewriteGenerator;
import com.example.kb.infrastructure.rag.RagPromptBuilder;
import com.example.kb.infrastructure.rag.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class ApplicationServiceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ApplicationServiceConfiguration.class);

    @Bean
    public RagRetrievalProperties ragRetrievalProperties(Environment environment) {
        return new RagRetrievalProperties(
                environment.getProperty("rag.retrieval.query-rewrite-enabled", Boolean.class),
                environment.getProperty("rag.retrieval.hyde-enabled", Boolean.class),
                environment.getProperty("rag.retrieval.multi-query-enabled", Boolean.class),
                environment.getProperty("rag.retrieval.bm25-enabled", Boolean.class),
                environment.getProperty("rag.retrieval.query-rewrite-history-message-limit", Integer.class),
                environment.getProperty("rag.retrieval.multi-query-count", Integer.class),
                environment.getProperty("rag.retrieval.dense-top-k", Integer.class),
                environment.getProperty("rag.retrieval.bm25-top-k", Integer.class),
                environment.getProperty("rag.retrieval.fusion-top-k", Integer.class),
                environment.getProperty("rag.retrieval.context-top-k", Integer.class),
                environment.getProperty("rag.retrieval.rrf-k", Integer.class),
                environment.getProperty("rag.retrieval.executor-core-size", Integer.class),
                environment.getProperty("rag.retrieval.executor-max-size", Integer.class),
                environment.getProperty("rag.retrieval.executor-queue-capacity", Integer.class)
        );
    }

    @Bean
    public KnowledgeBaseService knowledgeBaseService(KnowledgeBaseRepository knowledgeBaseRepository) {
        return new KnowledgeBaseService(knowledgeBaseRepository);
    }

    @Bean
    public KnowledgeFileService knowledgeFileService(
            KnowledgeFileRepository knowledgeFileRepository,
            ObjectStorage objectStorage,
            VectorIndexCleaner vectorIndexCleaner,
            KnowledgeFileIndexTaskService knowledgeFileIndexTaskService,
            DocumentChunkRepository documentChunkRepository,
            ChunkObjectStorage chunkObjectStorage,
            ChunkEnrichmentObjectStorage chunkEnrichmentObjectStorage,
            ChunkEnrichmentRepository chunkEnrichmentRepository
    ) {
        return new KnowledgeFileService(
                knowledgeFileRepository,
                objectStorage,
                vectorIndexCleaner,
                knowledgeFileIndexTaskService,
                documentChunkRepository,
                chunkObjectStorage,
                chunkEnrichmentObjectStorage,
                chunkEnrichmentRepository
        );
    }

    @Bean
    public KnowledgeFileIndexTaskService knowledgeFileIndexTaskService(
            KnowledgeFileIndexTaskRepository knowledgeFileIndexTaskRepository,
            IndexPipeline indexPipeline
    ) {
        return new KnowledgeFileIndexTaskService(knowledgeFileIndexTaskRepository, indexPipeline);
    }

    @Bean
    public DocumentChunkService documentChunkService(
            DocumentChunker documentChunker,
            DocumentChunkRepository documentChunkRepository,
            ChunkObjectStorage chunkObjectStorage
    ) {
        return new DocumentChunkService(documentChunker, documentChunkRepository, chunkObjectStorage);
    }

    @Bean
    public ChunkEnrichmentGenerator chunkEnrichmentGenerator(
            ChunkEnrichmentProperties properties,
            ChunkEnrichmentPromptBuilder promptBuilder,
            ObjectMapper objectMapper
    ) {
        if (!properties.enabled()) {
            log.warn("Chunk enrichment 生成器分支: enrichment 未启用，使用 Mock 生成器");
            return new MockChunkEnrichmentGenerator(properties);
        }
        if ((properties.apiKey() == null || properties.apiKey().isBlank()) && properties.mockWhenApiKeyMissing()) {
            log.warn("Chunk enrichment 生成器分支: apiKey 为空，使用 Mock 生成器");
            return new MockChunkEnrichmentGenerator(properties);
        }
        log.info("Chunk enrichment 生成器分支: 使用 AgentScope 生成器, provider={}, model={}",
                properties.provider(), properties.model());
        return new AgentScopeChunkEnrichmentGenerator(properties, promptBuilder, objectMapper);
    }

    @Bean
    public ChunkEnrichmentService chunkEnrichmentService(
            ChunkContentStorage chunkContentStorage,
            ChunkEnrichmentGenerator chunkEnrichmentGenerator,
            ChunkEnrichmentObjectStorage chunkEnrichmentObjectStorage,
            ChunkEnrichmentRepository chunkEnrichmentRepository
    ) {
        return new ChunkEnrichmentService(
                chunkContentStorage,
                chunkEnrichmentGenerator,
                chunkEnrichmentObjectStorage,
                chunkEnrichmentRepository
        );
    }

    @Bean
    public EmbeddingGenerator embeddingGenerator(EmbeddingProperties properties) {
        if (!properties.enabled()) {
            log.warn("Embedding 生成器分支: embedding 未启用，但第一版索引流程需要向量，仍创建 DashScope 生成器");
        }
        log.info("Embedding 生成器分支: 使用 DashScope, provider={}, model={}",
                properties.provider(), properties.model());
        return new DashScopeEmbeddingGenerator(properties);
    }

    @Bean
    public ChunkEmbeddingService chunkEmbeddingService(
            ChunkContentStorage chunkContentStorage,
            ChunkEnrichmentRepository chunkEnrichmentRepository,
            EmbeddingGenerator embeddingGenerator,
            VectorIndexWriter vectorIndexWriter,
            EmbeddingProperties embeddingProperties
    ) {
        return new ChunkEmbeddingService(
                chunkContentStorage,
                chunkEnrichmentRepository,
                embeddingGenerator,
                vectorIndexWriter,
                embeddingProperties.batchSize()
        );
    }

    @Bean
    public RagRouter ragRouter(
            RagProperties ragProperties,
            RagPromptBuilder ragPromptBuilder,
            ObjectMapper objectMapper
    ) {
        log.info("RAG Router 生成器分支: 使用 AgentScope, provider={}, model={}",
                ragProperties.provider(), ragProperties.routerModel());
        return new AgentScopeRagRouter(ragProperties, ragPromptBuilder, objectMapper);
    }

    @Bean
    public RagAnswerGenerator ragAnswerGenerator(
            RagProperties ragProperties,
            RagPromptBuilder ragPromptBuilder
    ) {
        log.info("RAG Answer 生成器分支: 使用 AgentScope, provider={}, model={}",
                ragProperties.provider(), ragProperties.answerModel());
        return new AgentScopeRagAnswerGenerator(ragProperties, ragPromptBuilder);
    }

    @Bean
    public QueryRewriteGenerator queryRewriteGenerator(
            RagProperties ragProperties,
            RagPromptBuilder ragPromptBuilder,
            ObjectMapper objectMapper
    ) {
        log.info("Query Rewrite 生成器分支: 使用 AgentScope, provider={}, model={}",
                ragProperties.provider(), ragProperties.answerModel());
        return new AgentScopeQueryRewriteGenerator(ragProperties, ragPromptBuilder, objectMapper);
    }

    @Bean
    public HydeGenerator hydeGenerator(
            RagProperties ragProperties,
            RagPromptBuilder ragPromptBuilder
    ) {
        log.info("HyDE 生成器分支: 使用 AgentScope, provider={}, model={}",
                ragProperties.provider(), ragProperties.answerModel());
        return new AgentScopeHydeGenerator(ragProperties, ragPromptBuilder);
    }

    @Bean
    public MultiQueryGenerator multiQueryGenerator(
            RagProperties ragProperties,
            RagPromptBuilder ragPromptBuilder,
            ObjectMapper objectMapper
    ) {
        log.info("Multi Query 生成器分支: 使用 AgentScope, provider={}, model={}",
                ragProperties.provider(), ragProperties.answerModel());
        return new AgentScopeMultiQueryGenerator(ragProperties, ragPromptBuilder, objectMapper);
    }

    @Bean
    public RrfFusionService rrfFusionService() {
        return new RrfFusionService();
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService ragRetrievalExecutorService(RagRetrievalProperties ragRetrievalProperties) {
        log.info("RAG 检索线程池初始化: coreSize={}, maxSize={}, queueCapacity={}",
                ragRetrievalProperties.safeExecutorCoreSize(),
                ragRetrievalProperties.safeExecutorMaxSize(),
                ragRetrievalProperties.safeExecutorQueueCapacity());
        return new ThreadPoolExecutor(
                ragRetrievalProperties.safeExecutorCoreSize(),
                ragRetrievalProperties.safeExecutorMaxSize(),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(ragRetrievalProperties.safeExecutorQueueCapacity()),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Bean
    public RagRetrievalService ragRetrievalService(
            QueryRewriteGenerator queryRewriteGenerator,
            HydeGenerator hydeGenerator,
            MultiQueryGenerator multiQueryGenerator,
            EmbeddingGenerator embeddingGenerator,
            VectorIndexSearcher vectorIndexSearcher,
            KeywordIndexSearcher keywordIndexSearcher,
            ConversationRetrievalTaskRepository conversationRetrievalTaskRepository,
            ConversationRetrievalTaskHitRepository conversationRetrievalTaskHitRepository,
            RrfFusionService rrfFusionService,
            RagRetrievalProperties ragRetrievalProperties,
            @Qualifier("ragRetrievalExecutorService") ExecutorService ragRetrievalExecutorService
    ) {
        return new RagRetrievalService(
                queryRewriteGenerator,
                hydeGenerator,
                multiQueryGenerator,
                embeddingGenerator,
                vectorIndexSearcher,
                keywordIndexSearcher,
                conversationRetrievalTaskRepository,
                conversationRetrievalTaskHitRepository,
                rrfFusionService,
                ragRetrievalProperties,
                ragRetrievalExecutorService
        );
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService ragSseExecutorService() {
        log.info("RAG SSE 线程池初始化");
        return new ThreadPoolExecutor(
                2,
                4,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Bean
    public ConversationService conversationService(
            ConversationRepository conversationRepository,
            ConversationMessageRepository conversationMessageRepository
    ) {
        return new ConversationService(conversationRepository, conversationMessageRepository);
    }

    @Bean
    public RagChatService ragChatService(
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
            RagAnswerGenerator ragAnswerGenerator,
            RagRetrievalProperties ragRetrievalProperties
    ) {
        return new RagChatService(
                conversationRepository,
                conversationMessageRepository,
                conversationRetrievalRepository,
                conversationRetrievalReferenceRepository,
                knowledgeBaseRepository,
                knowledgeFileRepository,
                documentChunkRepository,
                chunkContentStorage,
                ragRouter,
                ragRetrievalService,
                ragAnswerGenerator,
                ragRetrievalProperties.safeContextTopK()
        );
    }
}
