package com.example.kb.application.port;

import com.example.kb.domain.model.QueryIntent;
import com.example.kb.domain.model.RagRouterAction;

import java.math.BigDecimal;
import java.util.List;

public interface RagRouter {

    RouteResult route(RouteCommand command);

    record RouteCommand(
            String userQuestion,
            List<KnowledgeBaseOption> knowledgeBases
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
            BigDecimal confidence,
            String reason
    ) {
    }
}
