package com.example.kb.infrastructure.enrichment;

import java.util.List;

public record LlmChunkEnrichmentResponse(
        String summary,
        List<Question> questions
) {

    public record Question(
            String question,
            String type
    ) {
    }
}
