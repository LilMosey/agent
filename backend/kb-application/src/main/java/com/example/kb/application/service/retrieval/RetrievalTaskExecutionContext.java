package com.example.kb.application.service.retrieval;

import com.example.kb.application.service.RagRetrievalProperties;
import com.example.kb.application.service.RagRetrievalService;

import java.util.List;

public record RetrievalTaskExecutionContext(
        RagRetrievalService.RetrievalCommand command,
        RagRetrievalProperties properties,
        List<Long> knowledgeBaseIds
) {
}
