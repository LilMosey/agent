package com.example.kb.bootstrap;

import com.example.kb.application.port.ChunkContentStorage;
import com.example.kb.application.port.ChunkEnrichmentGenerator;
import com.example.kb.application.port.ChunkEnrichmentObjectStorage;
import com.example.kb.application.port.ChunkEnrichmentRepository;
import com.example.kb.application.port.ChunkObjectStorage;
import com.example.kb.application.port.DocumentChunkRepository;
import com.example.kb.application.port.DocumentChunker;
import com.example.kb.application.port.IndexPipeline;
import com.example.kb.application.port.KnowledgeBaseRepository;
import com.example.kb.application.port.KnowledgeFileRepository;
import com.example.kb.application.port.KnowledgeFileIndexTaskRepository;
import com.example.kb.application.port.ObjectStorage;
import com.example.kb.application.port.VectorIndexCleaner;
import com.example.kb.application.service.ChunkEnrichmentService;
import com.example.kb.application.service.KnowledgeBaseService;
import com.example.kb.application.service.DocumentChunkService;
import com.example.kb.application.service.KnowledgeFileIndexTaskService;
import com.example.kb.application.service.KnowledgeFileService;
import com.example.kb.infrastructure.enrichment.AgentScopeChunkEnrichmentGenerator;
import com.example.kb.infrastructure.enrichment.ChunkEnrichmentProperties;
import com.example.kb.infrastructure.enrichment.ChunkEnrichmentPromptBuilder;
import com.example.kb.infrastructure.enrichment.MockChunkEnrichmentGenerator;
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
}
