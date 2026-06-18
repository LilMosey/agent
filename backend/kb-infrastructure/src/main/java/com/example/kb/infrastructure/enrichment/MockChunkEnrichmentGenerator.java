package com.example.kb.infrastructure.enrichment;

import com.example.kb.application.port.ChunkEnrichmentGenerator;
import com.example.kb.domain.model.ChunkEnrichmentQuestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class MockChunkEnrichmentGenerator implements ChunkEnrichmentGenerator {

    private static final Logger log = LoggerFactory.getLogger(MockChunkEnrichmentGenerator.class);

    private final ChunkEnrichmentProperties properties;

    public MockChunkEnrichmentGenerator(ChunkEnrichmentProperties properties) {
        this.properties = properties;
    }

    @Override
    public GenerateResult generate(GenerateCommand command) {
        log.warn("Mock enrichment 生成器入参: knowledgeBaseId={}, fileId={}, chunkId={}, reason=apiKeyMissingOrMockEnabled",
                command.knowledgeBaseId(), command.fileId(), command.chunkId());
        String summary = truncate(command.chunkContent(), properties.summaryMaxChars());
        String titlePath = command.titlePath() == null || command.titlePath().isBlank() ? "当前章节" : command.titlePath();
        List<ChunkEnrichmentQuestion> questions = List.of(
                new ChunkEnrichmentQuestion("这段内容主要讲什么？", "summary"),
                new ChunkEnrichmentQuestion(titlePath + " 中有哪些关键信息？", "specific"),
                new ChunkEnrichmentQuestion("如果我要了解 " + command.filename() + " 的相关内容，应该关注什么？", "scenario")
        );
        log.warn("Mock enrichment 生成器出参: chunkId={}, summaryLength={}, questionCount={}",
                command.chunkId(), summary.length(), questions.size());
        return new GenerateResult(
                summary,
                questions,
                "mock",
                "mock-chunk-enrichment",
                properties.promptVersion()
        );
    }

    private String truncate(String content, int maxLength) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }
}
