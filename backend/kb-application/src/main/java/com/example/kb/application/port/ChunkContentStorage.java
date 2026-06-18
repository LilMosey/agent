package com.example.kb.application.port;

public interface ChunkContentStorage {

    String getChunkContent(String bucket, String objectKey);
}
