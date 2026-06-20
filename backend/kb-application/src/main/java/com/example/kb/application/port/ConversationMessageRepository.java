package com.example.kb.application.port;

import com.example.kb.domain.model.ConversationMessage;

import java.util.List;

public interface ConversationMessageRepository {

    ConversationMessage save(ConversationMessage message);

    List<ConversationMessage> findByConversationId(Long conversationId);

    Integer nextMessageOrder(Long conversationId);
}
