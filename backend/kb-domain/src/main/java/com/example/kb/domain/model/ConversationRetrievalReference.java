package com.example.kb.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ConversationRetrievalReference(
        Long id,
        Long conversationRetrievalId,
        Long knowledgeBaseId,
        Long fileId,
        Long chunkId,
        Integer chunkIndex,
        String titlePath,
        BigDecimal score,
        String contentPreview,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
