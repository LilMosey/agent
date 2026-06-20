package com.example.kb.application.service.retrieval;

import com.example.kb.domain.model.RetrievalTaskStatus;
import com.example.kb.domain.model.RetrievalTaskType;

public record RetrievalTaskDraft(
        RetrievalTaskType taskType,
        String queryText,
        RetrievalTaskStatus status,
        String errorMessage
) {

    public static RetrievalTaskDraft running(RetrievalTaskType taskType, String queryText) {
        return new RetrievalTaskDraft(taskType, queryText, RetrievalTaskStatus.RUNNING, null);
    }

    public static RetrievalTaskDraft failed(RetrievalTaskType taskType, String queryText, String errorMessage) {
        return new RetrievalTaskDraft(taskType, queryText, RetrievalTaskStatus.FAILED, errorMessage);
    }

    public static RetrievalTaskDraft skipped(RetrievalTaskType taskType, String queryText, String errorMessage) {
        return new RetrievalTaskDraft(taskType, queryText, RetrievalTaskStatus.SKIPPED, errorMessage);
    }
}
