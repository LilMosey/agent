package com.example.kb.application.service.retrieval;

import com.example.kb.application.port.EmbeddingGenerator;
import com.example.kb.application.port.QueryRewriteGenerator;
import com.example.kb.application.port.VectorIndexSearcher;
import com.example.kb.application.service.RagRetrievalService;
import com.example.kb.domain.model.RetrievalTaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class RewriteDenseRetrievalTaskExecutor extends AbstractDenseRetrievalTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(RewriteDenseRetrievalTaskExecutor.class);

    private final QueryRewriteGenerator queryRewriteGenerator;

    public RewriteDenseRetrievalTaskExecutor(
            QueryRewriteGenerator queryRewriteGenerator,
            EmbeddingGenerator embeddingGenerator,
            VectorIndexSearcher vectorIndexSearcher
    ) {
        super(embeddingGenerator, vectorIndexSearcher);
        this.queryRewriteGenerator = queryRewriteGenerator;
    }

    @Override
    public List<RetrievalTaskDraft> buildTaskDrafts(RetrievalTaskExecutionContext context) {
        List<RetrievalTaskDraft> drafts = new ArrayList<>();
        if (!context.properties().isQueryRewriteEnabled()) {
            return drafts;
        }
        try {
            QueryRewriteGenerator.RewriteResult rewriteResult = queryRewriteGenerator.rewrite(
                    new QueryRewriteGenerator.RewriteCommand(
                            context.command().userQuestion(),
                            context.command().queryIntent(),
                            context.command().recentMessages()
                    )
            );
            if (rewriteResult.rewrittenQuery().isBlank()
                    || rewriteResult.rewrittenQuery().equals(context.command().userQuestion())
                    || !Boolean.TRUE.equals(rewriteResult.changed())) {
                drafts.add(RetrievalTaskDraft.skipped(
                        RetrievalTaskType.REWRITE_DENSE,
                        context.command().userQuestion(),
                        "Query 改写未产生有效变化"
                ));
                return drafts;
            }
            drafts.add(RetrievalTaskDraft.running(RetrievalTaskType.REWRITE_DENSE, rewriteResult.rewrittenQuery()));
            return drafts;
        } catch (Exception exception) {
            log.error("构建 Query 改写任务异常: questionLength={}",
                    context.command().userQuestion().length(), exception);
            drafts.add(RetrievalTaskDraft.failed(
                    RetrievalTaskType.REWRITE_DENSE,
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
