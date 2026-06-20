package com.example.kb.application.service.retrieval;

import com.example.kb.application.port.EmbeddingGenerator;
import com.example.kb.application.port.VectorIndexSearcher;
import com.example.kb.application.service.RagRetrievalService;
import com.example.kb.domain.model.RetrievalTaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class OriginalDenseRetrievalTaskExecutor extends AbstractDenseRetrievalTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(OriginalDenseRetrievalTaskExecutor.class);

    public OriginalDenseRetrievalTaskExecutor(
            EmbeddingGenerator embeddingGenerator,
            VectorIndexSearcher vectorIndexSearcher
    ) {
        super(embeddingGenerator, vectorIndexSearcher);
    }

    @Override
    public List<RetrievalTaskDraft> buildTaskDrafts(RetrievalTaskExecutionContext context) {
        return List.of(running(RetrievalTaskType.ORIGINAL_DENSE, context.command().userQuestion()));
    }

    @Override
    public RagRetrievalService.RetrievalTaskReport execute(
            RetrievalTaskDraft taskDraft,
            RetrievalTaskExecutionContext context
    ) {
        return executeDenseTask(taskDraft, context, log);
    }
}
