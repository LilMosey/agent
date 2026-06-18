package com.example.kb.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.kb.application.port.ChunkEnrichmentRepository;
import com.example.kb.domain.model.ChunkEnrichment;
import com.example.kb.domain.model.EnrichmentStatus;
import com.example.kb.domain.model.EnrichmentStrategy;
import com.example.kb.infrastructure.persistence.entity.ChunkEnrichmentEntity;
import com.example.kb.infrastructure.persistence.mapper.ChunkEnrichmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class MybatisChunkEnrichmentRepository implements ChunkEnrichmentRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisChunkEnrichmentRepository.class);
    private static final int BATCH_INSERT_SIZE = 100;

    private final ChunkEnrichmentMapper chunkEnrichmentMapper;

    public MybatisChunkEnrichmentRepository(ChunkEnrichmentMapper chunkEnrichmentMapper) {
        this.chunkEnrichmentMapper = chunkEnrichmentMapper;
    }

    @Override
    public void deleteByFileId(Long fileId) {
        log.info("删除 enrichment 元数据入参: fileId={}", fileId);
        LambdaQueryWrapper<ChunkEnrichmentEntity> wrapper = new LambdaQueryWrapper<ChunkEnrichmentEntity>()
                .eq(ChunkEnrichmentEntity::getFileId, fileId);
        int deletedRows = chunkEnrichmentMapper.delete(wrapper);
        log.info("删除 enrichment 元数据出参: fileId={}, deletedRows={}", fileId, deletedRows);
    }

    @Override
    public ChunkEnrichment save(ChunkEnrichment chunkEnrichment) {
        log.info("保存 enrichment 元数据入参: fileId={}, chunkId={}, strategy={}, status={}",
                chunkEnrichment.fileId(), chunkEnrichment.chunkId(), chunkEnrichment.enrichmentStrategy().logName(), chunkEnrichment.status());
        ChunkEnrichmentEntity entity = toEntity(chunkEnrichment);
        chunkEnrichmentMapper.insert(entity);
        ChunkEnrichment saved = toDomain(entity);
        log.info("保存 enrichment 元数据出参: id={}, fileId={}, chunkId={}, status={}",
                saved.id(), saved.fileId(), saved.chunkId(), saved.status());
        return saved;
    }

    @Override
    public List<ChunkEnrichment> saveBatch(List<ChunkEnrichment> chunkEnrichments) {
        log.info("批量保存 enrichment 元数据入参: count={}", chunkEnrichments.size());
        if (chunkEnrichments.isEmpty()) {
            log.info("批量保存 enrichment 元数据分支: 空列表");
            return List.of();
        }
        List<ChunkEnrichmentEntity> entities = new ArrayList<>(chunkEnrichments.size());
        for (ChunkEnrichment chunkEnrichment : chunkEnrichments) {
            entities.add(toEntity(chunkEnrichment));
        }
        int insertedRows = 0;
        int batchCount = 0;
        for (int fromIndex = 0; fromIndex < entities.size(); fromIndex += BATCH_INSERT_SIZE) {
            int toIndex = Math.min(fromIndex + BATCH_INSERT_SIZE, entities.size());
            List<ChunkEnrichmentEntity> batchEntities = entities.subList(fromIndex, toIndex);
            insertedRows += chunkEnrichmentMapper.insertBatch(batchEntities);
            batchCount++;
        }
        List<ChunkEnrichment> savedEnrichments = entities.stream()
                .map(this::toDomain)
                .toList();
        log.info("批量保存 enrichment 元数据出参: insertedRows={}, count={}, batchSize={}, batchCount={}",
                insertedRows, savedEnrichments.size(), BATCH_INSERT_SIZE, batchCount);
        return savedEnrichments;
    }

    private ChunkEnrichment toDomain(ChunkEnrichmentEntity entity) {
        return new ChunkEnrichment(
                entity.getId(),
                entity.getKnowledgeBaseId(),
                entity.getFileId(),
                entity.getChunkId(),
                EnrichmentStrategy.valueOf(entity.getEnrichmentStrategy()),
                entity.getSummary(),
                entity.getQuestionsJson(),
                entity.getEmbeddingTextBucket(),
                entity.getEmbeddingTextObjectKey(),
                entity.getLlmProvider(),
                entity.getLlmModel(),
                entity.getPromptVersion(),
                EnrichmentStatus.valueOf(entity.getStatus()),
                entity.getErrorMessage(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ChunkEnrichmentEntity toEntity(ChunkEnrichment chunkEnrichment) {
        ChunkEnrichmentEntity entity = new ChunkEnrichmentEntity();
        entity.setId(chunkEnrichment.id());
        entity.setKnowledgeBaseId(chunkEnrichment.knowledgeBaseId());
        entity.setFileId(chunkEnrichment.fileId());
        entity.setChunkId(chunkEnrichment.chunkId());
        entity.setEnrichmentStrategy(chunkEnrichment.enrichmentStrategy().name());
        entity.setSummary(chunkEnrichment.summary());
        entity.setQuestionsJson(chunkEnrichment.questionsJson());
        entity.setEmbeddingTextBucket(chunkEnrichment.embeddingTextBucket());
        entity.setEmbeddingTextObjectKey(chunkEnrichment.embeddingTextObjectKey());
        entity.setLlmProvider(chunkEnrichment.llmProvider());
        entity.setLlmModel(chunkEnrichment.llmModel());
        entity.setPromptVersion(chunkEnrichment.promptVersion());
        entity.setStatus(chunkEnrichment.status().name());
        entity.setErrorMessage(chunkEnrichment.errorMessage());
        entity.setCreatedAt(chunkEnrichment.createdAt());
        entity.setUpdatedAt(chunkEnrichment.updatedAt());
        return entity;
    }
}
