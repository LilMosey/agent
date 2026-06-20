package com.example.kb.application.port;

import java.math.BigDecimal;
import java.util.List;

public interface VectorIndexSearcher {

    SearchResult search(SearchCommand command);

    record SearchCommand(
            List<Long> knowledgeBaseIds,
            List<Float> queryVector,
            int topK
    ) {
    }

    record SearchResult(
            List<SearchHit> hits
    ) {
    }

    record SearchHit(
            Long knowledgeBaseId,
            Long fileId,
            Long chunkId,
            Integer chunkIndex,
            BigDecimal score
    ) {
    }
}
