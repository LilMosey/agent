package com.example.kb.infrastructure.persistence.repository;

import com.example.kb.application.port.ConversationRetrievalRepository;
import com.example.kb.domain.model.ConversationRetrieval;
import com.example.kb.domain.model.QueryIntent;
import com.example.kb.domain.model.RagRouterAction;
import com.example.kb.infrastructure.persistence.entity.ConversationRetrievalEntity;
import com.example.kb.infrastructure.persistence.mapper.ConversationRetrievalMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisConversationRetrievalRepository implements ConversationRetrievalRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisConversationRetrievalRepository.class);

    private final ConversationRetrievalMapper conversationRetrievalMapper;
    private final ObjectMapper objectMapper;

    public MybatisConversationRetrievalRepository(
            ConversationRetrievalMapper conversationRetrievalMapper,
            ObjectMapper objectMapper
    ) {
        this.conversationRetrievalMapper = conversationRetrievalMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ConversationRetrieval save(ConversationRetrieval retrieval) {
        log.info("保存检索记录入参: conversationId={}, messageId={}, action={}, knowledgeBaseIds={}",
                retrieval.conversationId(), retrieval.messageId(), retrieval.action(), retrieval.knowledgeBaseIds());
        ConversationRetrievalEntity entity = toEntity(retrieval);
        LocalDateTime now = LocalDateTime.now();
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
        conversationRetrievalMapper.insert(entity);
        ConversationRetrieval saved = toDomain(entity);
        log.info("保存检索记录出参: id={}, conversationId={}, messageId={}",
                saved.id(), saved.conversationId(), saved.messageId());
        return saved;
    }

    @Override
    public Optional<ConversationRetrieval> findLatestWithReferencesByConversationId(Long conversationId) {
        log.info("查询最近有引用的检索记录入参: conversationId={}", conversationId);
        ConversationRetrievalEntity entity = conversationRetrievalMapper.selectLatestWithReferencesByConversationId(conversationId);
        if (entity == null) {
            log.info("查询最近有引用的检索记录出参: conversationId={}, found=false", conversationId);
            return Optional.empty();
        }
        ConversationRetrieval retrieval = toDomain(entity);
        log.info("查询最近有引用的检索记录出参: conversationId={}, retrievalId={}",
                conversationId, retrieval.id());
        return Optional.of(retrieval);
    }

    private ConversationRetrieval toDomain(ConversationRetrievalEntity entity) {
        return new ConversationRetrieval(
                entity.getId(),
                entity.getConversationId(),
                entity.getMessageId(),
                entity.getQueryText(),
                RagRouterAction.valueOf(entity.getAction()),
                readKnowledgeBaseIds(entity.getKnowledgeBaseIdsJson()),
                QueryIntent.valueOf(entity.getQueryIntent()),
                entity.getConfidence(),
                entity.getReason(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ConversationRetrievalEntity toEntity(ConversationRetrieval retrieval) {
        ConversationRetrievalEntity entity = new ConversationRetrievalEntity();
        entity.setId(retrieval.id());
        entity.setConversationId(retrieval.conversationId());
        entity.setMessageId(retrieval.messageId());
        entity.setQueryText(retrieval.queryText());
        entity.setAction(retrieval.action().name());
        entity.setKnowledgeBaseIdsJson(writeKnowledgeBaseIds(retrieval.knowledgeBaseIds()));
        entity.setQueryIntent(retrieval.queryIntent().name());
        entity.setConfidence(retrieval.confidence());
        entity.setReason(retrieval.reason());
        entity.setCreatedAt(retrieval.createdAt());
        entity.setUpdatedAt(retrieval.updatedAt());
        return entity;
    }

    private String writeKnowledgeBaseIds(List<Long> knowledgeBaseIds) {
        try {
            return objectMapper.writeValueAsString(knowledgeBaseIds);
        } catch (Exception e) {
            log.error("知识库 ID JSON 序列化异常: knowledgeBaseIds={}", knowledgeBaseIds, e);
            throw new IllegalStateException("知识库 ID JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    private List<Long> readKnowledgeBaseIds(String knowledgeBaseIdsJson) {
        try {
            return objectMapper.readValue(knowledgeBaseIdsJson, new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            log.error("知识库 ID JSON 反序列化异常: json={}", knowledgeBaseIdsJson, e);
            throw new IllegalStateException("知识库 ID JSON 反序列化失败: " + e.getMessage(), e);
        }
    }
}
