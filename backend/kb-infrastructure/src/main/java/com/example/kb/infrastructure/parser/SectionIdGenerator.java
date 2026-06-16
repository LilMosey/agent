package com.example.kb.infrastructure.parser;

public final class SectionIdGenerator {

    private SectionIdGenerator() {
    }

    public static String rootId(Long fileId) {
        return "file-" + fileId + "-section-root";
    }

    public static String sectionId(Long fileId, int orderIndex) {
        return "file-" + fileId + "-section-" + orderIndex;
    }
}
