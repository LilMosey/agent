package com.example.kb.application.port;

import com.example.kb.domain.model.FileType;

import java.io.InputStream;

public record DocumentParseCommand(
        Long knowledgeBaseId,
        Long fileId,
        String filename,
        FileType fileType,
        String contentType,
        InputStream inputStream
) {
}
