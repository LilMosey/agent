package com.example.kb.infrastructure.storage;

import com.example.kb.application.port.ChunkContentStorage;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Component
public class MinioChunkContentStorage implements ChunkContentStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioChunkContentStorage.class);

    private final MinioProperties minioProperties;
    private final MinioClient minioClient;

    public MinioChunkContentStorage(MinioProperties minioProperties) {
        this.minioProperties = minioProperties;
        this.minioClient = MinioClient.builder()
                .endpoint(minioProperties.endpoint())
                .credentials(minioProperties.accessKey(), minioProperties.secretKey())
                .build();
    }

    @Override
    public String getChunkContent(String bucket, String objectKey) {
        log.info("读取 chunk 正文入参: configuredBucket={}, bucket={}, objectKey={}", minioProperties.bucket(), bucket, objectKey);
        try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            log.info("读取 chunk 正文出参: bucket={}, objectKey={}, length={}", bucket, objectKey, content.length());
            return content;
        } catch (Exception exception) {
            log.error("读取 chunk 正文异常: bucket={}, objectKey={}", bucket, objectKey, exception);
            throw new IllegalStateException("读取 chunk 正文失败。", exception);
        }
    }
}
