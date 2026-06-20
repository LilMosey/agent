package com.example.kb.application.service.retrieval;

import com.example.kb.application.service.RagRetrievalService;

import java.util.List;

public interface RetrievalTaskExecutor {

    List<RetrievalTaskDraft> buildTaskDrafts(RetrievalTaskExecutionContext context);

    RagRetrievalService.RetrievalTaskReport execute(
            RetrievalTaskDraft taskDraft,
            RetrievalTaskExecutionContext context
    );
}
