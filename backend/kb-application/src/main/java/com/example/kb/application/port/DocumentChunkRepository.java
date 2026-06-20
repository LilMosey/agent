package com.example.kb.application.port;

import com.example.kb.domain.model.DocumentChunk;

import java.util.List;

public interface DocumentChunkRepository {

    void deleteByFileId(Long fileId);

    DocumentChunk save(DocumentChunk chunk);

    List<DocumentChunk> saveBatch(List<DocumentChunk> chunks);

    List<DocumentChunk> findByFileId(Long fileId);

    List<DocumentChunk> findByIds(List<Long> ids);
}
