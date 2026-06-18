package com.example.kb.domain.model;

public enum EnrichmentStrategy {
    HYBRID_TEXT("增强文本");

    private final String logName;

    EnrichmentStrategy(String logName) {
        this.logName = logName;
    }

    public String logName() {
        return logName;
    }
}
