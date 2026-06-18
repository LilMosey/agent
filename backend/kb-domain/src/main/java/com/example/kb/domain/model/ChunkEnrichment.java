package com.example.kb.domain.model;

import java.time.LocalDateTime;

public record ChunkEnrichment(
        Long id,
        Long knowledgeBaseId,
        Long fileId,
        Long chunkId,
        EnrichmentStrategy enrichmentStrategy,
        String summary,
        String questionsJson,
        String embeddingTextBucket,
        String embeddingTextObjectKey,
        String llmProvider,
        String llmModel,
        String promptVersion,
        EnrichmentStatus status,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
