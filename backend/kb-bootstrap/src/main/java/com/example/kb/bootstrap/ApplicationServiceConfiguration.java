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
import com.example.kb.application.port.DocumentChunkRepository;
import com.example.kb.application.port.DocumentChunker;
import com.example.kb.application.port.EmbeddingGenerator;
import com.example.kb.application.port.IndexPipeline;
import com.example.kb.application.port.KnowledgeBaseRepository;
import com.example.kb.application.port.KnowledgeFileRepository;
import com.example.kb.application.port.KnowledgeFileIndexTaskRepository;
import com.example.kb.application.port.ObjectStorage;
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
import com.example.kb.infrastructure.embedding.DashScopeEmbeddingGenerator;
import com.example.kb.infrastructure.embedding.EmbeddingProperties;
import com.example.kb.infrastructure.enrichment.AgentScopeChunkEnrichmentGenerator;
import com.example.kb.infrastructure.enrichment.ChunkEnrichmentProperties;
import com.example.kb.infrastructure.enrichment.ChunkEnrichmentPromptBuilder;
import com.example.kb.infrastructure.enrichment.MockChunkEnrichmentGenerator;
import com.example.kb.infrastructure.rag.AgentScopeRagAnswerGenerator;
import com.example.kb.infrastructure.rag.AgentScopeRagRouter;
import com.example.kb.infrastructure.rag.RagPromptBuilder;
import com.example.kb.infrastructure.rag.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationServiceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ApplicationServiceConfiguration.class);

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
            EmbeddingGenerator embeddingGenerator,
            VectorIndexSearcher vectorIndexSearcher,
            RagAnswerGenerator ragAnswerGenerator,
            RagProperties ragProperties
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
                embeddingGenerator,
                vectorIndexSearcher,
                ragAnswerGenerator,
                ragProperties.retrievalTopK(),
                ragProperties.contextTopK()
        );
    }
}
