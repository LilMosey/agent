package com.example.kb.application.service;

import com.example.kb.application.port.ChunkContentStorage;
import com.example.kb.application.port.ChunkEnrichmentGenerator;
import com.example.kb.application.port.ChunkEnrichmentObjectStorage;
import com.example.kb.application.port.ChunkEnrichmentRepository;
import com.example.kb.domain.model.ChunkEnrichment;
import com.example.kb.domain.model.ChunkEnrichmentQuestion;
import com.example.kb.domain.model.DocumentChunk;
import com.example.kb.domain.model.EnrichmentStatus;
import com.example.kb.domain.model.EnrichmentStrategy;
import com.example.kb.domain.model.KnowledgeFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;

public class ChunkEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(ChunkEnrichmentService.class);
    private static final int MAX_ERROR_MESSAGE_LENGTH = 2048;

    private final ChunkContentStorage chunkContentStorage;
    private final ChunkEnrichmentGenerator chunkEnrichmentGenerator;
    private final ChunkEnrichmentObjectStorage chunkEnrichmentObjectStorage;
    private final ChunkEnrichmentRepository chunkEnrichmentRepository;

    public ChunkEnrichmentService(
            ChunkContentStorage chunkContentStorage,
            ChunkEnrichmentGenerator chunkEnrichmentGenerator,
            ChunkEnrichmentObjectStorage chunkEnrichmentObjectStorage,
            ChunkEnrichmentRepository chunkEnrichmentRepository
    ) {
        this.chunkContentStorage = chunkContentStorage;
        this.chunkEnrichmentGenerator = chunkEnrichmentGenerator;
        this.chunkEnrichmentObjectStorage = chunkEnrichmentObjectStorage;
        this.chunkEnrichmentRepository = chunkEnrichmentRepository;
    }

    public void rebuildEnrichments(KnowledgeFile file, List<DocumentChunk> chunks) {
        log.info("重建 enrichment 入参: knowledgeBaseId={}, fileId={}, chunkCount={}",
                file.knowledgeBaseId(), file.id(), chunks.size());
        chunkEnrichmentObjectStorage.deleteEnrichmentsByFile(file.knowledgeBaseId(), file.id());
        chunkEnrichmentRepository.deleteByFileId(file.id());
        int readyCount = 0;
        int failedCount = 0;
        for (DocumentChunk chunk : chunks) {
            try {
                rebuildOne(file, chunk);
                readyCount++;
            } catch (Exception exception) {
                failedCount++;
                saveFailed(file, chunk, exception);
            }
        }
        log.info("重建 enrichment 出参: knowledgeBaseId={}, fileId={}, readyCount={}, failedCount={}",
                file.knowledgeBaseId(), file.id(), readyCount, failedCount);
    }

    private void rebuildOne(KnowledgeFile file, DocumentChunk chunk) {
        log.info("重建单个 enrichment 入参: fileId={}, chunkId={}, chunkIndex={}",
                file.id(), chunk.id(), chunk.chunkIndex());
        String chunkContent = chunkContentStorage.getChunkContent(chunk.storageBucket(), chunk.storageObjectKey());
        ChunkEnrichmentGenerator.GenerateResult result = chunkEnrichmentGenerator.generate(new ChunkEnrichmentGenerator.GenerateCommand(
                file.knowledgeBaseId(),
                file.id(),
                chunk.id(),
                file.originalFilename(),
                chunk.titlePath(),
                chunkContent
        ));
        String questionsJson = toQuestionsJson(result.questions());
        String embeddingText = buildEmbeddingText(chunk.titlePath(), result.summary(), result.questions(), chunkContent);
        ChunkEnrichmentObjectStorage.StoredEnrichmentObject storedObject = chunkEnrichmentObjectStorage.putEmbeddingText(
                new ChunkEnrichmentObjectStorage.PutEmbeddingTextCommand(
                        file.knowledgeBaseId(),
                        file.id(),
                        chunk.id(),
                        embeddingText
                )
        );
        LocalDateTime now = LocalDateTime.now();
        ChunkEnrichment enrichment = new ChunkEnrichment(
                null,
                file.knowledgeBaseId(),
                file.id(),
                chunk.id(),
                EnrichmentStrategy.HYBRID_TEXT,
                result.summary(),
                questionsJson,
                storedObject.bucket(),
                storedObject.objectKey(),
                result.llmProvider(),
                result.llmModel(),
                result.promptVersion(),
                EnrichmentStatus.READY,
                null,
                now,
                now
        );
        chunkEnrichmentRepository.save(enrichment);
        log.info("重建单个 enrichment 出参: fileId={}, chunkId={}, status={}", file.id(), chunk.id(), EnrichmentStatus.READY);
    }

    private void saveFailed(KnowledgeFile file, DocumentChunk chunk, Exception exception) {
        String errorMessage = truncateErrorMessage(exception.getMessage());
        log.error("重建单个 enrichment 异常: fileId={}, chunkId={}, errorMessage={}",
                file.id(), chunk.id(), errorMessage, exception);
        LocalDateTime now = LocalDateTime.now();
        ChunkEnrichment enrichment = new ChunkEnrichment(
                null,
                file.knowledgeBaseId(),
                file.id(),
                chunk.id(),
                EnrichmentStrategy.HYBRID_TEXT,
                null,
                null,
                chunk.storageBucket(),
                "",
                null,
                null,
                null,
                EnrichmentStatus.FAILED,
                errorMessage,
                now,
                now
        );
        chunkEnrichmentRepository.save(enrichment);
    }

    private String buildEmbeddingText(
            String titlePath,
            String summary,
            List<ChunkEnrichmentQuestion> questions,
            String chunkContent
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("标题路径：").append(titlePath == null || titlePath.isBlank() ? "无" : titlePath).append("\n\n");
        builder.append("摘要：\n").append(summary).append("\n\n");
        builder.append("可能问题：\n");
        for (int index = 0; index < questions.size(); index++) {
            builder.append(index + 1).append(". ").append(questions.get(index).question()).append("\n");
        }
        builder.append("\n原文：\n").append(chunkContent);
        return builder.toString();
    }

    private String toQuestionsJson(List<ChunkEnrichmentQuestion> questions) {
        StringBuilder builder = new StringBuilder();
        builder.append("[");
        for (int index = 0; index < questions.size(); index++) {
            ChunkEnrichmentQuestion question = questions.get(index);
            if (index > 0) {
                builder.append(",");
            }
            builder.append("{\"question\":\"")
                    .append(escapeJson(question.question()))
                    .append("\",\"type\":\"")
                    .append(escapeJson(question.type()))
                    .append("\"}");
        }
        builder.append("]");
        return builder.toString();
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String truncateErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "chunk enrichment 失败。";
        }
        if (message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }
}
