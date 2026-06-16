package com.example.kb.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.kb.application.port.KnowledgeFileRepository;
import com.example.kb.domain.model.FileStatus;
import com.example.kb.domain.model.FileType;
import com.example.kb.domain.model.KnowledgeFile;
import com.example.kb.infrastructure.persistence.entity.KnowledgeFileEntity;
import com.example.kb.infrastructure.persistence.mapper.KnowledgeFileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisKnowledgeFileRepository implements KnowledgeFileRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisKnowledgeFileRepository.class);

    private final KnowledgeFileMapper knowledgeFileMapper;

    public MybatisKnowledgeFileRepository(KnowledgeFileMapper knowledgeFileMapper) {
        this.knowledgeFileMapper = knowledgeFileMapper;
    }

    @Override
    public boolean existsByKnowledgeBaseIdAndFilename(Long knowledgeBaseId, String filename) {
        log.info("检查同名文件入参: knowledgeBaseId={}, filename={}", knowledgeBaseId, filename);
        LambdaQueryWrapper<KnowledgeFileEntity> wrapper = new LambdaQueryWrapper<KnowledgeFileEntity>()
                .eq(KnowledgeFileEntity::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeFileEntity::getOriginalFilename, filename);
        boolean exists = knowledgeFileMapper.selectCount(wrapper) > 0;
        log.info("检查同名文件出参: knowledgeBaseId={}, filename={}, exists={}", knowledgeBaseId, filename, exists);
        return exists;
    }

    @Override
    public KnowledgeFile save(KnowledgeFile file) {
        log.info("保存文件元数据入参: id={}, knowledgeBaseId={}, filename={}", file.id(), file.knowledgeBaseId(), file.originalFilename());
        KnowledgeFileEntity entity = toEntity(file);
        if (entity.getId() == null) {
            log.info("保存文件元数据分支: 新增文件, knowledgeBaseId={}, filename={}", entity.getKnowledgeBaseId(), entity.getOriginalFilename());
            knowledgeFileMapper.insert(entity);
        } else {
            log.info("保存文件元数据分支: 更新文件, id={}", entity.getId());
            knowledgeFileMapper.updateById(entity);
        }
        KnowledgeFile saved = toDomain(entity);
        log.info("保存文件元数据出参: id={}, filename={}, status={}", saved.id(), saved.originalFilename(), saved.fileStatus());
        return saved;
    }

    @Override
    public List<KnowledgeFile> search(Long knowledgeBaseId, String keyword, String status, int page, int size) {
        log.info("搜索文件元数据入参: knowledgeBaseId={}, keyword={}, status={}, page={}, size={}", knowledgeBaseId, keyword, status, page, size);
        LambdaQueryWrapper<KnowledgeFileEntity> wrapper = new LambdaQueryWrapper<KnowledgeFileEntity>()
                .eq(KnowledgeFileEntity::getKnowledgeBaseId, knowledgeBaseId)
                .orderByDesc(KnowledgeFileEntity::getCreatedAt);
        if (keyword != null && !keyword.isBlank()) {
            log.info("搜索文件元数据分支: 添加文件名关键字条件, keyword={}", keyword);
            wrapper.like(KnowledgeFileEntity::getOriginalFilename, keyword);
        } else {
            log.info("搜索文件元数据分支: 未添加文件名关键字条件");
        }
        if (status != null && !status.isBlank()) {
            log.info("搜索文件元数据分支: 添加状态条件, status={}", status);
            wrapper.eq(KnowledgeFileEntity::getFileStatus, status);
        } else {
            log.info("搜索文件元数据分支: 未添加状态条件");
        }
        Page<KnowledgeFileEntity> pageRequest = Page.of(page, size);
        List<KnowledgeFile> files = knowledgeFileMapper.selectPage(pageRequest, wrapper).getRecords().stream()
                .map(this::toDomain)
                .toList();
        log.info("搜索文件元数据出参: count={}", files.size());
        return files;
    }

    @Override
    public Optional<KnowledgeFile> findByKnowledgeBaseIdAndFileId(Long knowledgeBaseId, Long fileId) {
        log.info("按 ID 查询文件元数据入参: knowledgeBaseId={}, fileId={}", knowledgeBaseId, fileId);
        LambdaQueryWrapper<KnowledgeFileEntity> wrapper = new LambdaQueryWrapper<KnowledgeFileEntity>()
                .eq(KnowledgeFileEntity::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeFileEntity::getId, fileId);
        KnowledgeFileEntity entity = knowledgeFileMapper.selectOne(wrapper);
        Optional<KnowledgeFile> result = Optional.ofNullable(entity).map(this::toDomain);
        if (result.isPresent()) {
            log.info("按 ID 查询文件元数据分支: 命中, knowledgeBaseId={}, fileId={}", knowledgeBaseId, fileId);
        } else {
            log.warn("按 ID 查询文件元数据分支: 未命中, knowledgeBaseId={}, fileId={}", knowledgeBaseId, fileId);
        }
        return result;
    }

    @Override
    public void updateParseStatus(Long knowledgeBaseId, Long fileId, FileStatus fileStatus, String parseError, LocalDateTime updatedAt) {
        log.info("更新文件解析状态入参: knowledgeBaseId={}, fileId={}, fileStatus={}, hasParseError={}, updatedAt={}",
                knowledgeBaseId, fileId, fileStatus, parseError != null && !parseError.isBlank(), updatedAt);
        if (parseError == null || parseError.isBlank()) {
            log.info("更新文件解析状态分支: 无解析错误, knowledgeBaseId={}, fileId={}", knowledgeBaseId, fileId);
        } else {
            log.warn("更新文件解析状态分支: 存在解析错误, knowledgeBaseId={}, fileId={}, parseError={}", knowledgeBaseId, fileId, parseError);
        }
        LambdaUpdateWrapper<KnowledgeFileEntity> wrapper = new LambdaUpdateWrapper<KnowledgeFileEntity>()
                .eq(KnowledgeFileEntity::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeFileEntity::getId, fileId)
                .set(KnowledgeFileEntity::getFileStatus, fileStatus.name())
                .set(KnowledgeFileEntity::getParseError, parseError)
                .set(KnowledgeFileEntity::getUpdatedAt, updatedAt);
        int updatedRows = knowledgeFileMapper.update(null, wrapper);
        if (updatedRows > 0) {
            log.info("更新文件解析状态分支: 更新成功, knowledgeBaseId={}, fileId={}, updatedRows={}", knowledgeBaseId, fileId, updatedRows);
        } else {
            log.warn("更新文件解析状态分支: 未更新, 文件可能不存在, knowledgeBaseId={}, fileId={}", knowledgeBaseId, fileId);
        }
        log.info("更新文件解析状态出参: knowledgeBaseId={}, fileId={}, updatedRows={}", knowledgeBaseId, fileId, updatedRows);
    }

    @Override
    public void deleteByKnowledgeBaseIdAndFileId(Long knowledgeBaseId, Long fileId) {
        log.info("删除文件元数据入参: knowledgeBaseId={}, fileId={}", knowledgeBaseId, fileId);
        LambdaQueryWrapper<KnowledgeFileEntity> wrapper = new LambdaQueryWrapper<KnowledgeFileEntity>()
                .eq(KnowledgeFileEntity::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeFileEntity::getId, fileId);
        knowledgeFileMapper.delete(wrapper);
        log.info("删除文件元数据出参: knowledgeBaseId={}, fileId={}, deleted=true", knowledgeBaseId, fileId);
    }

    private KnowledgeFile toDomain(KnowledgeFileEntity entity) {
        return new KnowledgeFile(
                entity.getId(),
                entity.getKnowledgeBaseId(),
                entity.getOriginalFilename(),
                entity.getFileExt(),
                entity.getContentType(),
                entity.getFileSize(),
                entity.getChecksumSha256(),
                entity.getStorageBucket(),
                entity.getStorageObjectKey(),
                FileType.valueOf(entity.getFileType()),
                FileStatus.valueOf(entity.getFileStatus()),
                entity.getParseError(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private KnowledgeFileEntity toEntity(KnowledgeFile file) {
        KnowledgeFileEntity entity = new KnowledgeFileEntity();
        entity.setId(file.id());
        entity.setKnowledgeBaseId(file.knowledgeBaseId());
        entity.setOriginalFilename(file.originalFilename());
        entity.setFileExt(file.fileExt());
        entity.setContentType(file.contentType());
        entity.setFileSize(file.fileSize());
        entity.setChecksumSha256(file.checksumSha256());
        entity.setStorageBucket(file.storageBucket());
        entity.setStorageObjectKey(file.storageObjectKey());
        entity.setFileType(file.fileType().name());
        entity.setFileStatus(file.fileStatus().name());
        entity.setParseError(file.parseError());
        entity.setCreatedAt(file.createdAt());
        entity.setUpdatedAt(file.updatedAt());
        return entity;
    }
}
