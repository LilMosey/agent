package com.example.kb.application.port;

import com.example.kb.domain.model.ConversationRetrieval;

public interface ConversationRetrievalRepository {

    ConversationRetrieval save(ConversationRetrieval retrieval);
}
