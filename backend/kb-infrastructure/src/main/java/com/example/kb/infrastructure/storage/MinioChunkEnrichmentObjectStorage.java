package com.example.kb.infrastructure.storage;

import com.example.kb.application.port.ChunkEnrichmentObjectStorage;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@Component
public class MinioChunkEnrichmentObjectStorage implements ChunkEnrichmentObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioChunkEnrichmentObjectStorage.class);

    private final MinioProperties minioProperties;
    private final MinioClient minioClient;

    public MinioChunkEnrichmentObjectStorage(MinioProperties minioProperties) {
        this.minioProperties = minioProperties;
        this.minioClient = MinioClient.builder()
                .endpoint(minioProperties.endpoint())
                .credentials(minioProperties.accessKey(), minioProperties.secretKey())
                .build();
    }

    @Override
    public StoredEnrichmentObject putEmbeddingText(PutEmbeddingTextCommand command) {
        log.info("MinIO enrichment 上传入参: knowledgeBaseId={}, fileId={}, chunkId={}",
                command.knowledgeBaseId(), command.fileId(), command.chunkId());
        try {
            ensureBucketExists();
            byte[] bytes = command.content().getBytes(StandardCharsets.UTF_8);
            String objectKey = buildObjectKey(command.knowledgeBaseId(), command.fileId(), command.chunkId());
            ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
            PutObjectArgs putObjectArgs = PutObjectArgs.builder()
                    .bucket(minioProperties.bucket())
                    .object(objectKey)
                    .contentType("text/plain; charset=utf-8")
                    .stream(inputStream, bytes.length, -1)
                    .build();
            minioClient.putObject(putObjectArgs);
            log.info("MinIO enrichment 上传出参: bucket={}, objectKey={}, bytes={}", minioProperties.bucket(), objectKey, bytes.length);
            return new StoredEnrichmentObject(minioProperties.bucket(), objectKey);
        } catch (Exception exception) {
            log.error("MinIO enrichment 上传异常: knowledgeBaseId={}, fileId={}, chunkId={}",
                    command.knowledgeBaseId(), command.fileId(), command.chunkId(), exception);
            throw new IllegalStateException("enrichment 增强文本上传到 MinIO 失败。", exception);
        }
    }

    @Override
    public void deleteEnrichmentsByFile(Long knowledgeBaseId, Long fileId) {
        log.info("MinIO enrichment 批量删除入参: knowledgeBaseId={}, fileId={}", knowledgeBaseId, fileId);
        try {
            ensureBucketExists();
            String prefix = "chunk-enrichments/%d/%d/".formatted(knowledgeBaseId, fileId);
            Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(minioProperties.bucket())
                    .prefix(prefix)
                    .recursive(true)
                    .build());
            int deletedCount = 0;
            for (Result<Item> result : results) {
                Item item = result.get();
                RemoveObjectArgs removeObjectArgs = RemoveObjectArgs.builder()
                        .bucket(minioProperties.bucket())
                        .object(item.objectName())
                        .build();
                minioClient.removeObject(removeObjectArgs);
                deletedCount++;
            }
            log.info("MinIO enrichment 批量删除出参: knowledgeBaseId={}, fileId={}, deletedCount={}", knowledgeBaseId, fileId, deletedCount);
        } catch (Exception exception) {
            log.error("MinIO enrichment 批量删除异常: knowledgeBaseId={}, fileId={}", knowledgeBaseId, fileId, exception);
            throw new IllegalStateException("删除 MinIO enrichment 增强文本失败。", exception);
        }
    }

    private String buildObjectKey(Long knowledgeBaseId, Long fileId, Long chunkId) {
        return "chunk-enrichments/%d/%d/%d/embedding-text.txt".formatted(knowledgeBaseId, fileId, chunkId);
    }

    private void ensureBucketExists() throws Exception {
        BucketExistsArgs bucketExistsArgs = BucketExistsArgs.builder()
                .bucket(minioProperties.bucket())
                .build();
        boolean exists = minioClient.bucketExists(bucketExistsArgs);
        if (!exists) {
            MakeBucketArgs makeBucketArgs = MakeBucketArgs.builder()
                    .bucket(minioProperties.bucket())
                    .build();
            minioClient.makeBucket(makeBucketArgs);
        }
    }
}
