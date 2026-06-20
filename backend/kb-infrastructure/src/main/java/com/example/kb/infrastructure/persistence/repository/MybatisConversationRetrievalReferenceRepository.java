package com.example.kb.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.kb.application.port.ConversationRetrievalReferenceRepository;
import com.example.kb.domain.model.ConversationRetrievalReference;
import com.example.kb.infrastructure.persistence.entity.ConversationRetrievalReferenceEntity;
import com.example.kb.infrastructure.persistence.mapper.ConversationRetrievalReferenceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public class MybatisConversationRetrievalReferenceRepository implements ConversationRetrievalReferenceRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisConversationRetrievalReferenceRepository.class);
    private static final int BATCH_INSERT_SIZE = 100;

    private final ConversationRetrievalReferenceMapper conversationRetrievalReferenceMapper;

    public MybatisConversationRetrievalReferenceRepository(
            ConversationRetrievalReferenceMapper conversationRetrievalReferenceMapper
    ) {
        this.conversationRetrievalReferenceMapper = conversationRetrievalReferenceMapper;
    }

    @Override
    public void saveBatch(List<ConversationRetrievalReference> references) {
        log.info("批量保存引用记录入参: count={}", references.size());
        if (references.isEmpty()) {
            log.info("批量保存引用记录分支: 空列表");
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<ConversationRetrievalReferenceEntity> entities = new ArrayList<>(references.size());
        for (ConversationRetrievalReference reference : references) {
            ConversationRetrievalReferenceEntity entity = toEntity(reference);
            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(now);
            }
            entity.setUpdatedAt(now);
            entities.add(entity);
        }
        int insertedRows = 0;
        int batchCount = 0;
        for (int fromIndex = 0; fromIndex < entities.size(); fromIndex += BATCH_INSERT_SIZE) {
            int toIndex = Math.min(fromIndex + BATCH_INSERT_SIZE, entities.size());
            List<ConversationRetrievalReferenceEntity> batchEntities = entities.subList(fromIndex, toIndex);
            insertedRows += conversationRetrievalReferenceMapper.insertBatch(batchEntities);
            batchCount++;
        }
        log.info("批量保存引用记录出参: insertedRows={}, batchSize={}, batchCount={}",
                insertedRows, BATCH_INSERT_SIZE, batchCount);
    }

    @Override
    public List<ConversationRetrievalReference> findByRetrievalId(Long retrievalId) {
        log.info("查询引用记录入参: retrievalId={}", retrievalId);
        LambdaQueryWrapper<ConversationRetrievalReferenceEntity> wrapper =
                new LambdaQueryWrapper<ConversationRetrievalReferenceEntity>()
                        .eq(ConversationRetrievalReferenceEntity::getConversationRetrievalId, retrievalId)
                        .orderByAsc(ConversationRetrievalReferenceEntity::getId);
        List<ConversationRetrievalReference> references = conversationRetrievalReferenceMapper.selectList(wrapper).stream()
                .map(this::toDomain)
                .toList();
        log.info("查询引用记录出参: retrievalId={}, count={}", retrievalId, references.size());
        return references;
    }

    private ConversationRetrievalReference toDomain(ConversationRetrievalReferenceEntity entity) {
        return new ConversationRetrievalReference(
                entity.getId(),
                entity.getConversationRetrievalId(),
                entity.getKnowledgeBaseId(),
                entity.getFileId(),
                entity.getChunkId(),
                entity.getChunkIndex(),
                entity.getTitlePath(),
                entity.getScore(),
                entity.getContentPreview(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ConversationRetrievalReferenceEntity toEntity(ConversationRetrievalReference reference) {
        ConversationRetrievalReferenceEntity entity = new ConversationRetrievalReferenceEntity();
        entity.setId(reference.id());
        entity.setConversationRetrievalId(reference.conversationRetrievalId());
        entity.setKnowledgeBaseId(reference.knowledgeBaseId());
        entity.setFileId(reference.fileId());
        entity.setChunkId(reference.chunkId());
        entity.setChunkIndex(reference.chunkIndex());
        entity.setTitlePath(reference.titlePath());
        entity.setScore(reference.score());
        entity.setContentPreview(reference.contentPreview());
        entity.setCreatedAt(reference.createdAt());
        entity.setUpdatedAt(reference.updatedAt());
        return entity;
    }
}
