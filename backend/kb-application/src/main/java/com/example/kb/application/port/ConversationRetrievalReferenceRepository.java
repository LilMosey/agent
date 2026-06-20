package com.example.kb.application.port;

import com.example.kb.domain.model.ConversationRetrievalReference;

import java.util.List;

public interface ConversationRetrievalReferenceRepository {

    void saveBatch(List<ConversationRetrievalReference> references);

    List<ConversationRetrievalReference> findByRetrievalId(Long retrievalId);
}
