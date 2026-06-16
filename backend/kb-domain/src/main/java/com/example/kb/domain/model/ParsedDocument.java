package com.example.kb.domain.model;

import java.util.List;
import java.util.Map;

public record ParsedDocument(
        Long knowledgeBaseId,
        Long fileId,
        String filename,
        FileType fileType,
        String title,
        List<DocumentSection> sections,
        Map<String, String> metadata
) {
}
