package com.example.kb.application.service;

import com.example.kb.application.port.ConversationMessageRepository;
import com.example.kb.application.port.ConversationRepository;
import com.example.kb.domain.model.Conversation;
import com.example.kb.domain.model.ConversationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final String DEFAULT_TITLE = "新会话";

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository conversationMessageRepository;

    public ConversationService(
            ConversationRepository conversationRepository,
            ConversationMessageRepository conversationMessageRepository
    ) {
        this.conversationRepository = conversationRepository;
        this.conversationMessageRepository = conversationMessageRepository;
    }

    public Conversation createConversation() {
        log.info("创建会话入参: title={}", DEFAULT_TITLE);
        Conversation conversation = new Conversation(null, DEFAULT_TITLE, null, null, null);
        Conversation saved = conversationRepository.save(conversation);
        log.info("创建会话出参: id={}, title={}", saved.id(), saved.title());
        return saved;
    }

    public List<Conversation> listConversations() {
        log.info("查询会话列表入参: none");
        List<Conversation> conversations = conversationRepository.findAllOrderByUpdatedAtDesc();
        log.info("查询会话列表出参: count={}", conversations.size());
        return conversations;
    }

    public List<ConversationMessage> listMessages(Long conversationId) {
        log.info("查询会话消息列表入参: conversationId={}", conversationId);
        conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        List<ConversationMessage> messages = conversationMessageRepository.findByConversationId(conversationId);
        log.info("查询会话消息列表出参: conversationId={}, count={}", conversationId, messages.size());
        return messages;
    }

    public Conversation updateTitle(Long conversationId, String title) {
        String normalizedTitle = title == null ? "" : title.trim();
        log.info("修改会话名称入参: conversationId={}, title={}", conversationId, normalizedTitle);
        if (normalizedTitle.isBlank()) {
            throw new IllegalArgumentException("会话名称不能为空。");
        }
        conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        conversationRepository.updateTitle(conversationId, normalizedTitle);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        log.info("修改会话名称出参: conversationId={}, title={}", conversation.id(), conversation.title());
        return conversation;
    }

    public void deleteConversation(Long conversationId) {
        log.info("删除会话入参: conversationId={}", conversationId);
        conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        conversationRepository.softDeleteById(conversationId);
        log.info("删除会话出参: conversationId={}, deleted=true", conversationId);
    }
}
