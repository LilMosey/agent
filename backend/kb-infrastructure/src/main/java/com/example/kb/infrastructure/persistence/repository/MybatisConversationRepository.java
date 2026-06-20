package com.example.kb.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.kb.application.port.ConversationRepository;
import com.example.kb.domain.model.Conversation;
import com.example.kb.infrastructure.persistence.entity.ConversationEntity;
import com.example.kb.infrastructure.persistence.mapper.ConversationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisConversationRepository implements ConversationRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisConversationRepository.class);

    private final ConversationMapper conversationMapper;

    public MybatisConversationRepository(ConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    @Override
    public Conversation save(Conversation conversation) {
        log.info("保存会话入参: id={}, title={}", conversation.id(), conversation.title());
        ConversationEntity entity = toEntity(conversation);
        LocalDateTime now = LocalDateTime.now();
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
        if (entity.getId() == null) {
            log.info("保存会话分支: 新增会话, title={}", entity.getTitle());
            conversationMapper.insert(entity);
        } else {
            log.info("保存会话分支: 更新会话, id={}", entity.getId());
            conversationMapper.updateById(entity);
        }
        Conversation saved = toDomain(entity);
        log.info("保存会话出参: id={}, title={}", saved.id(), saved.title());
        return saved;
    }

    @Override
    public Optional<Conversation> findById(Long id) {
        log.info("按 ID 查询会话入参: id={}", id);
        LambdaQueryWrapper<ConversationEntity> wrapper = new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getId, id)
                .isNull(ConversationEntity::getDeletedAt);
        ConversationEntity entity = conversationMapper.selectOne(wrapper);
        Optional<Conversation> result = Optional.ofNullable(entity).map(this::toDomain);
        log.info("按 ID 查询会话出参: id={}, found={}", id, result.isPresent());
        return result;
    }

    @Override
    public List<Conversation> findAllOrderByUpdatedAtDesc() {
        log.info("查询会话列表入参: none");
        LambdaQueryWrapper<ConversationEntity> wrapper = new LambdaQueryWrapper<ConversationEntity>()
                .isNull(ConversationEntity::getDeletedAt)
                .orderByDesc(ConversationEntity::getUpdatedAt);
        List<Conversation> conversations = conversationMapper.selectList(wrapper).stream()
                .map(this::toDomain)
                .toList();
        log.info("查询会话列表出参: count={}", conversations.size());
        return conversations;
    }

    @Override
    public void updateTitle(Long id, String title) {
        log.info("更新会话标题入参: id={}, title={}", id, title);
        ConversationEntity entity = new ConversationEntity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(entity);
        log.info("更新会话标题出参: id={}", id);
    }

    @Override
    public void softDeleteById(Long id) {
        log.info("软删除会话入参: id={}", id);
        ConversationEntity entity = new ConversationEntity();
        LocalDateTime now = LocalDateTime.now();
        entity.setId(id);
        entity.setUpdatedAt(now);
        entity.setDeletedAt(now);
        conversationMapper.updateById(entity);
        log.info("软删除会话出参: id={}, deletedAt={}", id, now);
    }

    private Conversation toDomain(ConversationEntity entity) {
        return new Conversation(
                entity.getId(),
                entity.getTitle(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }

    private ConversationEntity toEntity(Conversation conversation) {
        ConversationEntity entity = new ConversationEntity();
        entity.setId(conversation.id());
        entity.setTitle(conversation.title());
        entity.setCreatedAt(conversation.createdAt());
        entity.setUpdatedAt(conversation.updatedAt());
        entity.setDeletedAt(conversation.deletedAt());
        return entity;
    }
}
