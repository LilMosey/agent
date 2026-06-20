package com.example.kb.application.service.retrieval;

import com.example.kb.application.port.EmbeddingGenerator;
import com.example.kb.application.port.VectorIndexSearcher;
import com.example.kb.application.service.RagRetrievalService;
import com.example.kb.domain.model.RetrievalTaskType;
import org.slf4j.Logger;

import java.time.LocalDateTime;
import java.util.List;

public abstract class AbstractDenseRetrievalTaskExecutor implements RetrievalTaskExecutor {

    private final EmbeddingGenerator embeddingGenerator;
    private final VectorIndexSearcher vectorIndexSearcher;

    protected AbstractDenseRetrievalTaskExecutor(
            EmbeddingGenerator embeddingGenerator,
            VectorIndexSearcher vectorIndexSearcher
    ) {
        this.embeddingGenerator = embeddingGenerator;
        this.vectorIndexSearcher = vectorIndexSearcher;
    }

    protected RagRetrievalService.RetrievalTaskReport executeDenseTask(
            RetrievalTaskDraft taskDraft,
            RetrievalTaskExecutionContext context,
            Logger log
    ) {
        LocalDateTime startedAt = LocalDateTime.now();
        log.info("执行 Dense 检索任务入参: taskType={}, queryLength={}, knowledgeBaseIds={}",
                taskDraft.taskType(), taskDraft.queryText().length(), context.knowledgeBaseIds());
        try {
            EmbeddingGenerator.GenerateEmbeddingsResult embeddingsResult = embeddingGenerator.generate(
                    new EmbeddingGenerator.GenerateEmbeddingsCommand(List.of(taskDraft.queryText()))
            );
            List<Float> queryVector = embeddingsResult.items().get(0).vector();
            VectorIndexSearcher.SearchResult searchResult = vectorIndexSearcher.search(
                    new VectorIndexSearcher.SearchCommand(
                            context.knowledgeBaseIds(),
                            queryVector,
                            context.properties().safeDenseTopK()
                    )
            );
            RagRetrievalService.RetrievalTaskReport report = RagRetrievalService.RetrievalTaskReport.success(
                    taskDraft,
                    searchResult.hits(),
                    startedAt,
                    LocalDateTime.now()
            );
            log.info("执行 Dense 检索任务出参: taskType={}, hitCount={}",
                    report.taskType(), report.hits().size());
            return report;
        } catch (Exception exception) {
            log.error("执行 Dense 检索任务异常: taskType={}, queryLength={}",
                    taskDraft.taskType(), taskDraft.queryText().length(), exception);
            return RagRetrievalService.RetrievalTaskReport.failed(
                    RetrievalTaskDraft.failed(taskDraft.taskType(), taskDraft.queryText(), exception.getMessage()),
                    startedAt,
                    LocalDateTime.now()
            );
        }
    }

    protected RetrievalTaskDraft running(RetrievalTaskType taskType, String queryText) {
        return RetrievalTaskDraft.running(taskType, queryText);
    }
}
