package com.example.kb.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.kb.application.port.ConversationMessageRepository;
import com.example.kb.domain.model.ConversationMessage;
import com.example.kb.domain.model.MessageRole;
import com.example.kb.infrastructure.persistence.entity.ConversationMessageEntity;
import com.example.kb.infrastructure.persistence.mapper.ConversationMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class MybatisConversationMessageRepository implements ConversationMessageRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisConversationMessageRepository.class);

    private final ConversationMessageMapper conversationMessageMapper;

    public MybatisConversationMessageRepository(ConversationMessageMapper conversationMessageMapper) {
        this.conversationMessageMapper = conversationMessageMapper;
    }

    @Override
    public ConversationMessage save(ConversationMessage message) {
        log.info("保存会话消息入参: conversationId={}, role={}, messageOrder={}",
                message.conversationId(), message.role(), message.messageOrder());
        ConversationMessageEntity entity = toEntity(message);
        LocalDateTime now = LocalDateTime.now();
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
        conversationMessageMapper.insert(entity);
        ConversationMessage saved = toDomain(entity);
        log.info("保存会话消息出参: id={}, conversationId={}, role={}",
                saved.id(), saved.conversationId(), saved.role());
        return saved;
    }

    @Override
    public List<ConversationMessage> findByConversationId(Long conversationId) {
        log.info("查询会话消息入参: conversationId={}", conversationId);
        LambdaQueryWrapper<ConversationMessageEntity> wrapper = new LambdaQueryWrapper<ConversationMessageEntity>()
                .eq(ConversationMessageEntity::getConversationId, conversationId)
                .orderByAsc(ConversationMessageEntity::getMessageOrder);
        List<ConversationMessage> messages = conversationMessageMapper.selectList(wrapper).stream()
                .map(this::toDomain)
                .toList();
        log.info("查询会话消息出参: conversationId={}, count={}", conversationId, messages.size());
        return messages;
    }

    @Override
    public Integer nextMessageOrder(Long conversationId) {
        log.info("查询下一条消息序号入参: conversationId={}", conversationId);
        Integer messageOrder = conversationMessageMapper.selectNextMessageOrder(conversationId);
        log.info("查询下一条消息序号出参: conversationId={}, messageOrder={}", conversationId, messageOrder);
        return messageOrder;
    }

    private ConversationMessage toDomain(ConversationMessageEntity entity) {
        return new ConversationMessage(
                entity.getId(),
                entity.getConversationId(),
                MessageRole.valueOf(entity.getRole()),
                entity.getContent(),
                entity.getMessageOrder(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ConversationMessageEntity toEntity(ConversationMessage message) {
        ConversationMessageEntity entity = new ConversationMessageEntity();
        entity.setId(message.id());
        entity.setConversationId(message.conversationId());
        entity.setRole(message.role().name());
        entity.setContent(message.content());
        entity.setMessageOrder(message.messageOrder());
        entity.setCreatedAt(message.createdAt());
        entity.setUpdatedAt(message.updatedAt());
        return entity;
    }
}
