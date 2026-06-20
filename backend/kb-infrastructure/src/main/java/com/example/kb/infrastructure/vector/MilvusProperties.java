package com.example.kb.infrastructure.vector;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.vector.milvus")
public record MilvusProperties(
        String host,
        int port,
        String database,
        String collectionPrefix,
        String collectionName,
        Boolean bm25Enabled
) {

    public String uri() {
        return "http://" + host + ":" + port;
    }

    public String collectionName() {
        if (collectionName != null && !collectionName.isBlank()) {
            return collectionName;
        }
        return collectionPrefix + "_chunk";
    }

    public boolean isBm25Enabled() {
        return bm25Enabled != null && bm25Enabled;
    }
}
