package com.example.kb.application.service;

import com.example.kb.application.port.ChunkEnrichmentObjectStorage;
import com.example.kb.application.port.ChunkEnrichmentRepository;
import com.example.kb.application.port.ChunkObjectStorage;
import com.example.kb.application.port.DocumentChunkRepository;
import com.example.kb.application.port.KnowledgeFileRepository;
import com.example.kb.application.port.ObjectStorage;
import com.example.kb.application.port.VectorIndexCleaner;
import com.example.kb.domain.model.ChunkConfig;
import com.example.kb.domain.model.ChunkStrategy;
import com.example.kb.domain.model.FileStatus;
import com.example.kb.domain.model.FileType;
import com.example.kb.domain.model.KnowledgeFile;
import com.example.kb.domain.service.FileSignaturePolicy;
import com.example.kb.domain.service.FileTypePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

public class KnowledgeFileService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeFileService.class);

    private final KnowledgeFileRepository fileRepository;
    private final ObjectStorage objectStorage;
    private final VectorIndexCleaner vectorIndexCleaner;
    private final KnowledgeFileIndexTaskService indexTaskService;
    private final DocumentChunkRepository documentChunkRepository;
    private final ChunkObjectStorage chunkObjectStorage;
    private final ChunkEnrichmentObjectStorage chunkEnrichmentObjectStorage;
    private final ChunkEnrichmentRepository chunkEnrichmentRepository;
    private final FileTypePolicy fileTypePolicy = new FileTypePolicy();
    private final FileSignaturePolicy fileSignaturePolicy = new FileSignaturePolicy();

    public KnowledgeFileService(
            KnowledgeFileRepository fileRepository,
            ObjectStorage objectStorage,
            VectorIndexCleaner vectorIndexCleaner,
            KnowledgeFileIndexTaskService indexTaskService,
            DocumentChunkRepository documentChunkRepository,
            ChunkObjectStorage chunkObjectStorage,
            ChunkEnrichmentObjectStorage chunkEnrichmentObjectStorage,
            ChunkEnrichmentRepository chunkEnrichmentRepository
    ) {
        this.fileRepository = fileRepository;
        this.objectStorage = objectStorage;
        this.vectorIndexCleaner = vectorIndexCleaner;
        this.indexTaskService = indexTaskService;
        this.documentChunkRepository = documentChunkRepository;
        this.chunkObjectStorage = chunkObjectStorage;
        this.chunkEnrichmentObjectStorage = chunkEnrichmentObjectStorage;
        this.chunkEnrichmentRepository = chunkEnrichmentRepository;
    }

    public KnowledgeFile upload(
            Long knowledgeBaseId,
            String filename,
            String contentType,
            InputStream inputStream,
            long size,
            ChunkStrategy chunkStrategy,
            Integer chunkSize,
            Integer chunkOverlap
    ) {
        log.info("上传文件入参: knowledgeBaseId={}, filename={}, contentType={}, size={}, chunkStrategy={}, chunkSize={}, chunkOverlap={}",
                knowledgeBaseId, filename, contentType, size, chunkStrategyLogName(chunkStrategy), chunkSize, chunkOverlap);
        if (fileRepository.existsByKnowledgeBaseIdAndFilename(knowledgeBaseId, filename)) {
            log.warn("上传文件分支: 同名文件已存在, knowledgeBaseId={}, filename={}", knowledgeBaseId, filename);
            throw new IllegalArgumentException("同一知识库下已存在同名文件，请先删除旧文件。");
        } else {
            log.info("上传文件分支: 同名检查通过, knowledgeBaseId={}, filename={}", knowledgeBaseId, filename);
        }

        FileType fileType = fileTypePolicy.detect(filename);
        log.info("上传文件分支: 文件类型识别成功, filename={}, fileType={}", filename, fileType);
        ChunkConfig chunkConfig = ChunkConfig.normalize(knowledgeBaseId, null, chunkStrategy, chunkSize, chunkOverlap);
        log.info("上传文件分支: chunk 配置归一化成功, strategy={}, size={}, overlap={}",
                chunkConfig.chunkStrategy().logName(), chunkConfig.chunkSize(), chunkConfig.chunkOverlap());
        byte[] content;
        try {
            content = inputStream.readAllBytes();
            log.info("上传文件分支: 文件字节读取成功, filename={}, byteLength={}", filename, content.length);
        } catch (Exception exception) {
            log.error("上传文件异常: 文件字节读取失败, filename={}", filename, exception);
            throw new IllegalArgumentException("文件读取失败。", exception);
        }
        fileSignaturePolicy.validate(fileType, content);
        log.info("上传文件分支: 文件签名校验通过, filename={}, fileType={}", filename, fileType);
        ObjectStorage.PutObjectCommand command = new ObjectStorage.PutObjectCommand(
                knowledgeBaseId,
                filename,
                contentType,
                new ByteArrayInputStream(content),
                content.length
        );
        ObjectStorage.StoredObject storedObject = objectStorage.putObject(command);
        log.info("上传文件分支: 对象存储写入成功, bucket={}, objectKey={}, checksum={}", storedObject.bucket(), storedObject.objectKey(), storedObject.checksumSha256());
        LocalDateTime now = LocalDateTime.now();
        KnowledgeFile file = new KnowledgeFile(
                null,
                knowledgeBaseId,
                filename,
                fileTypePolicy.extensionOf(filename),
                contentType,
                content.length,
                storedObject.checksumSha256(),
                storedObject.bucket(),
                storedObject.objectKey(),
                fileType,
                FileStatus.PENDING_PARSE,
                chunkConfig.chunkStrategy(),
                chunkConfig.chunkSize(),
                chunkConfig.chunkOverlap(),
                null,
                now,
                now
        );
        KnowledgeFile saved = fileRepository.save(file);
        log.info("上传文件出参: id={}, knowledgeBaseId={}, filename={}, status={}", saved.id(), saved.knowledgeBaseId(), saved.originalFilename(), saved.fileStatus());
        indexTaskService.createPendingTask(saved.knowledgeBaseId(), saved.id());
        log.info("上传文件分支: 已创建索引任务, fileId={}", saved.id());
        return saved;
    }

    private String chunkStrategyLogName(ChunkStrategy chunkStrategy) {
        if (chunkStrategy == null) {
            return "未传入，使用默认策略";
        }
        return chunkStrategy.logName();
    }

    public List<KnowledgeFile> search(Long knowledgeBaseId, String keyword, String status, int page, int size) {
        log.info("查询文件列表入参: knowledgeBaseId={}, keyword={}, status={}, page={}, size={}", knowledgeBaseId, keyword, status, page, size);
        List<KnowledgeFile> files = fileRepository.search(knowledgeBaseId, keyword, status, page, size);
        log.info("查询文件列表出参: count={}", files.size());
        return files;
    }

    public KnowledgeFile get(Long knowledgeBaseId, Long fileId) {
        log.info("查询文件详情入参: knowledgeBaseId={}, fileId={}", knowledgeBaseId, fileId);
        KnowledgeFile file = fileRepository.findByKnowledgeBaseIdAndFileId(knowledgeBaseId, fileId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在。"));
        log.info("查询文件详情出参: id={}, filename={}, status={}", file.id(), file.originalFilename(), file.fileStatus());
        return file;
    }

    public InputStream download(Long knowledgeBaseId, Long fileId) {
        log.info("下载文件入参: knowledgeBaseId={}, fileId={}", knowledgeBaseId, fileId);
        KnowledgeFile file = get(knowledgeBaseId, fileId);
        InputStream inputStream = objectStorage.getObject(file.storageBucket(), file.storageObjectKey());
        log.info("下载文件出参: bucket={}, objectKey={}", file.storageBucket(), file.storageObjectKey());
        return inputStream;
    }

    public void delete(Long knowledgeBaseId, Long fileId) {
        log.info("删除文件入参: knowledgeBaseId={}, fileId={}", knowledgeBaseId, fileId);
        KnowledgeFile file = get(knowledgeBaseId, fileId);
        log.info("删除文件分支: 开始清理向量索引, fileId={}", fileId);
        vectorIndexCleaner.deleteByFileId(fileId);
        log.info("删除文件分支: 开始删除 enrichment 增强文本, knowledgeBaseId={}, fileId={}", knowledgeBaseId, fileId);
        chunkEnrichmentObjectStorage.deleteEnrichmentsByFile(knowledgeBaseId, fileId);
        log.info("删除文件分支: 开始删除 enrichment 元数据, fileId={}", fileId);
        chunkEnrichmentRepository.deleteByFileId(fileId);
        log.info("删除文件分支: 开始删除 chunk 正文, knowledgeBaseId={}, fileId={}", knowledgeBaseId, fileId);
        chunkObjectStorage.deleteChunksByFile(knowledgeBaseId, fileId);
        log.info("删除文件分支: 开始删除 chunk 元数据, fileId={}", fileId);
        documentChunkRepository.deleteByFileId(fileId);
        log.info("删除文件分支: 开始删除对象存储, bucket={}, objectKey={}", file.storageBucket(), file.storageObjectKey());
        objectStorage.deleteObject(file.storageBucket(), file.storageObjectKey());
        log.info("删除文件分支: 开始删除数据库元数据, knowledgeBaseId={}, fileId={}", knowledgeBaseId, fileId);
        fileRepository.deleteByKnowledgeBaseIdAndFileId(knowledgeBaseId, fileId);
        log.info("删除文件出参: knowledgeBaseId={}, fileId={}, deleted=true", knowledgeBaseId, fileId);
    }
}
