package com.example.kb.domain.model;

import java.time.LocalDateTime;

public record ConversationMessage(
        Long id,
        Long conversationId,
        MessageRole role,
        String content,
        Integer messageOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
