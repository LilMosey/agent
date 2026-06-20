package com.example.kb.application.port;

import com.example.kb.domain.model.Conversation;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository {

    Conversation save(Conversation conversation);

    Optional<Conversation> findById(Long id);

    List<Conversation> findAllOrderByUpdatedAtDesc();

    void updateTitle(Long id, String title);

    void softDeleteById(Long id);
}
