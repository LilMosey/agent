package com.example.kb.application.service.retrieval;

import com.example.kb.application.port.KeywordIndexSearcher;
import com.example.kb.application.port.VectorIndexSearcher;
import com.example.kb.application.service.RagRetrievalService;
import com.example.kb.domain.model.RetrievalTaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Bm25RetrievalTaskExecutor implements RetrievalTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(Bm25RetrievalTaskExecutor.class);

    private final KeywordIndexSearcher keywordIndexSearcher;

    public Bm25RetrievalTaskExecutor(KeywordIndexSearcher keywordIndexSearcher) {
        this.keywordIndexSearcher = keywordIndexSearcher;
    }

    @Override
    public List<RetrievalTaskDraft> buildTaskDrafts(RetrievalTaskExecutionContext context) {
        if (!context.properties().isBm25Enabled()) {
            return List.of();
        }
        return List.of(RetrievalTaskDraft.running(
                RetrievalTaskType.BM25,
                context.command().userQuestion()
        ));
    }

    @Override
    public RagRetrievalService.RetrievalTaskReport execute(
            RetrievalTaskDraft taskDraft,
            RetrievalTaskExecutionContext context
    ) {
        LocalDateTime startedAt = LocalDateTime.now();
        log.info("执行 BM25 检索任务入参: taskType={}, queryLength={}, knowledgeBaseIds={}",
                taskDraft.taskType(), taskDraft.queryText().length(), context.knowledgeBaseIds());
        try {
            KeywordIndexSearcher.KeywordSearchResult searchResult = keywordIndexSearcher.search(
                    new KeywordIndexSearcher.KeywordSearchCommand(
                            context.knowledgeBaseIds(),
                            taskDraft.queryText(),
                            context.properties().safeBm25TopK()
                    )
            );
            List<VectorIndexSearcher.SearchHit> hits = new ArrayList<>(searchResult.hits().size());
            for (KeywordIndexSearcher.KeywordSearchHit hit : searchResult.hits()) {
                hits.add(hit.toVectorSearchHit());
            }
            RagRetrievalService.RetrievalTaskReport report = RagRetrievalService.RetrievalTaskReport.success(
                    taskDraft,
                    hits,
                    startedAt,
                    LocalDateTime.now()
            );
            log.info("执行 BM25 检索任务出参: taskType={}, hitCount={}",
                    report.taskType(), report.hits().size());
            return report;
        } catch (Exception exception) {
            log.error("执行 BM25 检索任务异常: taskType={}, queryLength={}",
                    taskDraft.taskType(), taskDraft.queryText().length(), exception);
            return RagRetrievalService.RetrievalTaskReport.failed(
                    RetrievalTaskDraft.failed(taskDraft.taskType(), taskDraft.queryText(), exception.getMessage()),
                    startedAt,
                    LocalDateTime.now()
            );
        }
    }
}
