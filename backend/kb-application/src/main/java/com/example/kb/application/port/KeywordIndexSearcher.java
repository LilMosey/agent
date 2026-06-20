package com.example.kb.application.port;

import java.math.BigDecimal;
import java.util.List;

public interface KeywordIndexSearcher {

    KeywordSearchResult search(KeywordSearchCommand command);

    record KeywordSearchCommand(
            List<Long> knowledgeBaseIds,
            String queryText,
            int topK
    ) {
    }

    record KeywordSearchResult(
            List<KeywordSearchHit> hits
    ) {
    }

    record KeywordSearchHit(
            Long knowledgeBaseId,
            Long fileId,
            Long chunkId,
            Integer chunkIndex,
            BigDecimal score
    ) {
        public VectorIndexSearcher.SearchHit toVectorSearchHit() {
            return new VectorIndexSearcher.SearchHit(knowledgeBaseId, fileId, chunkId, chunkIndex, score);
        }
    }
}
