package com.example.kb.application.service;

import com.example.kb.application.port.ChunkContentStorage;
import com.example.kb.application.port.ChunkEnrichmentRepository;
import com.example.kb.application.port.EmbeddingGenerator;
import com.example.kb.application.port.VectorIndexWriter;
import com.example.kb.domain.model.ChunkEnrichment;
import com.example.kb.domain.model.DocumentChunk;
import com.example.kb.domain.model.EnrichmentStatus;
import com.example.kb.domain.model.KnowledgeFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChunkEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkEmbeddingService.class);
    private static final String SOURCE_ENRICHMENT_TEXT = "ENRICHMENT_TEXT";
    private static final String SOURCE_ORIGINAL_CHUNK = "ORIGINAL_CHUNK";

    private final ChunkContentStorage chunkContentStorage;
    private final ChunkEnrichmentRepository chunkEnrichmentRepository;
    private final EmbeddingGenerator embeddingGenerator;
    private final VectorIndexWriter vectorIndexWriter;
    private final int batchSize;

    public ChunkEmbeddingService(
            ChunkContentStorage chunkContentStorage,
            ChunkEnrichmentRepository chunkEnrichmentRepository,
            EmbeddingGenerator embeddingGenerator,
            VectorIndexWriter vectorIndexWriter,
            int batchSize
    ) {
        this.chunkContentStorage = chunkContentStorage;
        this.chunkEnrichmentRepository = chunkEnrichmentRepository;
        this.embeddingGenerator = embeddingGenerator;
        this.vectorIndexWriter = vectorIndexWriter;
        this.batchSize = batchSize <= 0 ? 10 : batchSize;
    }

    public void rebuildEmbeddings(KnowledgeFile file, List<DocumentChunk> chunks) {
        log.info("重建 embedding 入参: knowledgeBaseId={}, fileId={}, chunkCount={}",
                file.knowledgeBaseId(), file.id(), chunks.size());
        if (chunks.isEmpty()) {
            log.info("重建 embedding 分支: chunk 为空，跳过向量写入, fileId={}", file.id());
            return;
        }
        Map<Long, ChunkEnrichment> enrichmentMap = buildEnrichmentMap(file.id());
        List<EmbeddingText> embeddingTexts = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            embeddingTexts.add(resolveEmbeddingText(chunk, enrichmentMap.get(chunk.id())));
        }
        List<VectorIndexWriter.VectorChunk> vectorChunks = new ArrayList<>(embeddingTexts.size());
        for (int fromIndex = 0; fromIndex < embeddingTexts.size(); fromIndex += batchSize) {
            int toIndex = Math.min(fromIndex + batchSize, embeddingTexts.size());
            List<EmbeddingText> batchTexts = embeddingTexts.subList(fromIndex, toIndex);
            vectorChunks.addAll(generateBatchVectors(batchTexts));
        }
        vectorIndexWriter.upsertChunks(new VectorIndexWriter.UpsertChunksCommand(
                file.knowledgeBaseId(),
                file.id(),
                vectorChunks
        ));
        log.info("重建 embedding 出参: knowledgeBaseId={}, fileId={}, vectorCount={}",
                file.knowledgeBaseId(), file.id(), vectorChunks.size());
    }

    private Map<Long, ChunkEnrichment> buildEnrichmentMap(Long fileId) {
        List<ChunkEnrichment> enrichments = chunkEnrichmentRepository.findByFileId(fileId);
        Map<Long, ChunkEnrichment> enrichmentMap = new HashMap<>();
        for (ChunkEnrichment enrichment : enrichments) {
            enrichmentMap.put(enrichment.chunkId(), enrichment);
        }
        return enrichmentMap;
    }

    private EmbeddingText resolveEmbeddingText(DocumentChunk chunk, ChunkEnrichment enrichment) {
        if (enrichment != null
                && EnrichmentStatus.READY.equals(enrichment.status())
                && enrichment.embeddingTextBucket() != null
                && !enrichment.embeddingTextBucket().isBlank()
                && enrichment.embeddingTextObjectKey() != null
                && !enrichment.embeddingTextObjectKey().isBlank()) {
            String content = chunkContentStorage.getChunkContent(enrichment.embeddingTextBucket(), enrichment.embeddingTextObjectKey());
            log.info("embedding 文本选择分支: 使用 enrichment 增强文本, chunkId={}", chunk.id());
            return new EmbeddingText(chunk, SOURCE_ENRICHMENT_TEXT, content);
        }
        String content = chunkContentStorage.getChunkContent(chunk.storageBucket(), chunk.storageObjectKey());
        log.info("embedding 文本选择分支: 使用原始 chunk 正文, chunkId={}", chunk.id());
        return new EmbeddingText(chunk, SOURCE_ORIGINAL_CHUNK, content);
    }

    private List<VectorIndexWriter.VectorChunk> generateBatchVectors(List<EmbeddingText> embeddingTexts) {
        List<String> texts = new ArrayList<>(embeddingTexts.size());
        for (EmbeddingText embeddingText : embeddingTexts) {
            texts.add(embeddingText.content());
        }
        EmbeddingGenerator.GenerateEmbeddingsResult result = embeddingGenerator.generate(
                new EmbeddingGenerator.GenerateEmbeddingsCommand(texts)
        );
        if (result.items().size() != embeddingTexts.size()) {
            throw new IllegalStateException("embedding 返回数量不一致，入参=" + embeddingTexts.size() + "，出参=" + result.items().size());
        }
        List<VectorIndexWriter.VectorChunk> vectorChunks = new ArrayList<>(result.items().size());
        for (EmbeddingGenerator.EmbeddingItem item : result.items()) {
            if (item.textIndex() < 0 || item.textIndex() >= embeddingTexts.size()) {
                throw new IllegalStateException("embedding textIndex 越界: " + item.textIndex());
            }
            EmbeddingText embeddingText = embeddingTexts.get(item.textIndex());
            DocumentChunk chunk = embeddingText.chunk();
            vectorChunks.add(new VectorIndexWriter.VectorChunk(
                    chunk.id(),
                    chunk.chunkIndex(),
                    embeddingText.source(),
                    chunk.contentHash(),
                    embeddingText.content(),
                    item.vector()
            ));
        }
        return vectorChunks;
    }

    private record EmbeddingText(
            DocumentChunk chunk,
            String source,
            String content
    ) {
    }
}
