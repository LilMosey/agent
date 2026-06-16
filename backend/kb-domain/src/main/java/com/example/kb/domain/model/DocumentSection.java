package com.example.kb.domain.model;

import java.util.Map;

public record DocumentSection(
        String id,
        String parentId,
        Integer level,
        String title,
        String content,
        Integer orderIndex,
        Map<String, String> metadata
) {
}
