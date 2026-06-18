package com.example.kb.application.port;

public interface ChunkEnrichmentObjectStorage {

    StoredEnrichmentObject putEmbeddingText(PutEmbeddingTextCommand command);

    void deleteEnrichmentsByFile(Long knowledgeBaseId, Long fileId);

    record PutEmbeddingTextCommand(
            Long knowledgeBaseId,
            Long fileId,
            Long chunkId,
            String content
    ) {
    }

    record StoredEnrichmentObject(
            String bucket,
            String objectKey
    ) {
    }
}
