package com.example.kb.application.port;

import com.example.kb.domain.model.QueryIntent;
import com.example.kb.domain.model.RagRouterAction;
import com.example.kb.domain.model.ConversationMessage;

import java.math.BigDecimal;
import java.util.List;

public interface RagRouter {

    RouteResult route(RouteCommand command);

    record RouteCommand(
            String userQuestion,
            List<KnowledgeBaseOption> knowledgeBases,
            List<ConversationMessage> recentMessages,
            PreviousRagContext previousRagContext
    ) {
    }

    record KnowledgeBaseOption(
            Long id,
            String name,
            String description
    ) {
    }

    record RouteResult(
            RagRouterAction action,
            List<Long> knowledgeBaseIds,
            QueryIntent queryIntent,
            String searchQuery,
            Boolean reusePrevious,
            String reusePolicy,
            BigDecimal confidence,
            String reason
    ) {
    }

    record PreviousRagContext(
            Boolean available,
            String sourceQuestion,
            String sourceAnswer,
            List<PreviousReferenceContext> references
    ) {
    }

    record PreviousReferenceContext(
            Integer referenceNo,
            String fileName,
            String titlePath,
            Integer chunkIndex,
            String content
    ) {
    }
}
