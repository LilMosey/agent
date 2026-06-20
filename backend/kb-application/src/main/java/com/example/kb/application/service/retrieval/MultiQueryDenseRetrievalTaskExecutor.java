package com.example.kb.application.service.retrieval;

import com.example.kb.application.port.EmbeddingGenerator;
import com.example.kb.application.port.MultiQueryGenerator;
import com.example.kb.application.port.VectorIndexSearcher;
import com.example.kb.application.service.RagRetrievalService;
import com.example.kb.domain.model.RetrievalTaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class MultiQueryDenseRetrievalTaskExecutor extends AbstractDenseRetrievalTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(MultiQueryDenseRetrievalTaskExecutor.class);

    private final MultiQueryGenerator multiQueryGenerator;

    public MultiQueryDenseRetrievalTaskExecutor(
            MultiQueryGenerator multiQueryGenerator,
            EmbeddingGenerator embeddingGenerator,
            VectorIndexSearcher vectorIndexSearcher
    ) {
        super(embeddingGenerator, vectorIndexSearcher);
        this.multiQueryGenerator = multiQueryGenerator;
    }

    @Override
    public List<RetrievalTaskDraft> buildTaskDrafts(RetrievalTaskExecutionContext context) {
        List<RetrievalTaskDraft> drafts = new ArrayList<>();
        if (!context.properties().isMultiQueryEnabled()) {
            return drafts;
        }
        try {
            MultiQueryGenerator.MultiQueryResult multiQueryResult = multiQueryGenerator.generate(
                    new MultiQueryGenerator.MultiQueryCommand(
                            context.command().userQuestion(),
                            context.properties().safeMultiQueryCount()
                    )
            );
            for (String query : multiQueryResult.queries()) {
                if (!query.equals(context.command().userQuestion())) {
                    drafts.add(RetrievalTaskDraft.running(RetrievalTaskType.MULTI_QUERY_DENSE, query));
                }
            }
            return drafts;
        } catch (Exception exception) {
            log.error("构建 Multi Query 任务异常: questionLength={}",
                    context.command().userQuestion().length(), exception);
            drafts.add(RetrievalTaskDraft.failed(
                    RetrievalTaskType.MULTI_QUERY_DENSE,
                    context.command().userQuestion(),
                    exception.getMessage()
            ));
            return drafts;
        }
    }

    @Override
    public RagRetrievalService.RetrievalTaskReport execute(
            RetrievalTaskDraft taskDraft,
            RetrievalTaskExecutionContext context
    ) {
        return executeDenseTask(taskDraft, context, log);
    }
}
