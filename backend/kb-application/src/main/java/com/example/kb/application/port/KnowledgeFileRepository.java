package com.example.kb.application.port;

import com.example.kb.domain.model.FileStatus;
import com.example.kb.domain.model.KnowledgeFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface KnowledgeFileRepository {

    boolean existsByKnowledgeBaseIdAndFilename(Long knowledgeBaseId, String filename);

    KnowledgeFile save(KnowledgeFile file);

    List<KnowledgeFile> search(Long knowledgeBaseId, String keyword, String status, int page, int size);

    Optional<KnowledgeFile> findByKnowledgeBaseIdAndFileId(Long knowledgeBaseId, Long fileId);

    Optional<KnowledgeFile> findById(Long fileId);

    void updateParseStatus(Long knowledgeBaseId, Long fileId, FileStatus fileStatus, String parseError, LocalDateTime updatedAt);

    void deleteByKnowledgeBaseIdAndFileId(Long knowledgeBaseId, Long fileId);
}
