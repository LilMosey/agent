package com.example.kb.infrastructure.enrichment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.enrichment")
public record ChunkEnrichmentProperties(
        boolean enabled,
        String provider,
        String model,
        String apiKey,
        String baseUrl,
        int maxQuestions,
        int summaryMaxChars,
        String promptVersion,
        boolean mockWhenApiKeyMissing
) {
}
