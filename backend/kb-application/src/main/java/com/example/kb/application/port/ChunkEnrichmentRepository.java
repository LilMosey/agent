package com.example.kb.application.port;

import com.example.kb.domain.model.ChunkEnrichment;

public interface ChunkEnrichmentRepository {

    void deleteByFileId(Long fileId);

    ChunkEnrichment save(ChunkEnrichment chunkEnrichment);
}
