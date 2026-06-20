package com.example.kb.domain.model;

import java.time.LocalDateTime;

public record Conversation(
        Long id,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {
}
