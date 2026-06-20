package com.example.kb.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ConversationRetrieval(
        Long id,
        Long conversationId,
        Long messageId,
        String queryText,
        RagRouterAction action,
        List<Long> knowledgeBaseIds,
        QueryIntent queryIntent,
        BigDecimal confidence,
        String reason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
