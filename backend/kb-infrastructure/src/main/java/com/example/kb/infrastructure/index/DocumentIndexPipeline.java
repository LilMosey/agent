package com.example.kb.infrastructure.index;

import com.example.kb.application.port.DocumentCleaner;
import com.example.kb.application.port.DocumentParseCommand;
import com.example.kb.application.port.DocumentParser;
import com.example.kb.application.port.DocumentParserRegistry;
import com.example.kb.application.port.IndexPipeline;
import com.example.kb.application.port.KnowledgeFileRepository;
import com.example.kb.application.port.ObjectStorage;
import com.example.kb.domain.model.FileStatus;
import com.example.kb.domain.model.DocumentChunk;
import com.example.kb.domain.model.KnowledgeFile;
import com.example.kb.domain.model.KnowledgeFileIndexTask;
import com.example.kb.domain.model.ParsedDocument;
import com.example.kb.application.service.ChunkEnrichmentService;
import com.example.kb.application.service.DocumentChunkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class DocumentIndexPipeline implements IndexPipeline {

    private static final Logger log = LoggerFactory.getLogger(DocumentIndexPipeline.class);
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1024;

    private final KnowledgeFileRepository fileRepository;
    private final ObjectStorage objectStorage;
    private final DocumentParserRegistry parserRegistry;
    private final DocumentCleaner documentCleaner;
    private final DocumentChunkService documentChunkService;
    private final ChunkEnrichmentService chunkEnrichmentService;

    public DocumentIndexPipeline(
            KnowledgeFileRepository fileRepository,
            ObjectStorage objectStorage,
            DocumentParserRegistry parserRegistry,
            DocumentCleaner documentCleaner,
            DocumentChunkService documentChunkService,
            ChunkEnrichmentService chunkEnrichmentService
    ) {
        this.fileRepository = fileRepository;
        this.objectStorage = objectStorage;
        this.parserRegistry = parserRegistry;
        this.documentCleaner = documentCleaner;
        this.documentChunkService = documentChunkService;
        this.chunkEnrichmentService = chunkEnrichmentService;
    }

    @Override
    public IndexPipeline.IndexPipelineResult execute(KnowledgeFileIndexTask task) {
        log.info("索引 Pipeline 入参: taskId={}, fileId={}, knowledgeBaseId={}", task.id(), task.fileId(), task.knowledgeBaseId());
        try {
            KnowledgeFile file = fileRepository.findByKnowledgeBaseIdAndFileId(task.knowledgeBaseId(), task.fileId())
                    .orElseThrow(() -> new IllegalArgumentException("文件不存在，无法解析。"));
            log.info("索引 Pipeline 分支: 文件元数据查询成功, taskId={}, fileId={}, filename={}, fileType={}, status={}",
                    task.id(), file.id(), file.originalFilename(), file.fileType(), file.fileStatus());

            fileRepository.updateParseStatus(file.knowledgeBaseId(), file.id(), FileStatus.PARSING, null, LocalDateTime.now());
            log.info("索引 Pipeline 分支: 文件状态已更新为 PARSING, fileId={}", file.id());

            DocumentParser parser = parserRegistry.getParser(file.fileType());
            try (InputStream inputStream = objectStorage.getObject(file.storageBucket(), file.storageObjectKey())) {
                DocumentParseCommand command = new DocumentParseCommand(
                        file.knowledgeBaseId(),
                        file.id(),
                        file.originalFilename(),
                        file.fileType(),
                        file.contentType(),
                        inputStream
                );
                ParsedDocument parsedDocument = parser.parse(command);
                ParsedDocument cleanedDocument = documentCleaner.clean(parsedDocument);
                int sectionCount = cleanedDocument.sections().size();
                List<DocumentChunk> chunks = documentChunkService.rebuildChunks(file, cleanedDocument);
                int chunkCount = chunks.size();
                chunkEnrichmentService.rebuildEnrichments(file, chunks);
                fileRepository.updateParseStatus(file.knowledgeBaseId(), file.id(), FileStatus.READY, null, LocalDateTime.now());
                String message = "文档解析、清洗、chunk 和 enrichment 处理完成，sectionCount=" + sectionCount + ", chunkCount=" + chunkCount;
                log.info("索引 Pipeline 出参: taskId={}, fileId={}, title={}, sectionCount={}, chunkCount={}, status=SUCCESS",
                        task.id(), file.id(), cleanedDocument.title(), sectionCount, chunkCount);
                return IndexPipeline.IndexPipelineResult.success(message);
            }
        } catch (Exception exception) {
            String errorMessage = truncateErrorMessage(exception.getMessage());
            log.error("索引 Pipeline 异常: taskId={}, fileId={}, errorMessage={}", task.id(), task.fileId(), errorMessage, exception);
            fileRepository.updateParseStatus(task.knowledgeBaseId(), task.fileId(), FileStatus.PARSE_FAILED, errorMessage, LocalDateTime.now());
            return IndexPipeline.IndexPipelineResult.failed(errorMessage);
        }
    }

    private String truncateErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "文档解析失败。";
        }
        if (message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }
}
