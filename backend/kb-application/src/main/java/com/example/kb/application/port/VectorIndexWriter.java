package com.example.kb.application.port;

import java.util.List;

public interface VectorIndexWriter {

    void upsertChunks(UpsertChunksCommand command);

    record UpsertChunksCommand(
            Long knowledgeBaseId,
            Long fileId,
            List<VectorChunk> chunks
    ) {
    }

    record VectorChunk(
            Long chunkId,
            int chunkIndex,
            String embeddingSource,
            String contentHash,
            String content,
            List<Float> vector
    ) {
    }
}
