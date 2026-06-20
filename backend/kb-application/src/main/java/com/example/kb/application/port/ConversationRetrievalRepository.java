package com.example.kb.application.port;

import com.example.kb.domain.model.ConversationRetrieval;

import java.util.Optional;

public interface ConversationRetrievalRepository {

    ConversationRetrieval save(ConversationRetrieval retrieval);

    Optional<ConversationRetrieval> findLatestWithReferencesByConversationId(Long conversationId);
}
