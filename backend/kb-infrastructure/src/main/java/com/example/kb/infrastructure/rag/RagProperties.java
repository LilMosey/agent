package com.example.kb.infrastructure.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "rag.query")
public record RagProperties(
        String provider,
        String routerModel,
        String answerModel,
        String apiKey,
        String baseUrl,
        BigDecimal routerConfidenceThreshold,
        Integer retrievalTopK,
        Integer contextTopK
) {
}
