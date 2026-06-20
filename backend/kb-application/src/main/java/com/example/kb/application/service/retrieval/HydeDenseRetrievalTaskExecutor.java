package com.example.kb.application.service.retrieval;

import com.example.kb.application.port.EmbeddingGenerator;
import com.example.kb.application.port.HydeGenerator;
import com.example.kb.application.port.VectorIndexSearcher;
import com.example.kb.application.service.RagRetrievalService;
import com.example.kb.domain.model.RetrievalTaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class HydeDenseRetrievalTaskExecutor extends AbstractDenseRetrievalTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(HydeDenseRetrievalTaskExecutor.class);

    private final HydeGenerator hydeGenerator;

    public HydeDenseRetrievalTaskExecutor(
            HydeGenerator hydeGenerator,
            EmbeddingGenerator embeddingGenerator,
            VectorIndexSearcher vectorIndexSearcher
    ) {
        super(embeddingGenerator, vectorIndexSearcher);
        this.hydeGenerator = hydeGenerator;
    }

    @Override
    public List<RetrievalTaskDraft> buildTaskDrafts(RetrievalTaskExecutionContext context) {
        List<RetrievalTaskDraft> drafts = new ArrayList<>();
        if (!context.properties().isHydeEnabled()) {
            return drafts;
        }
        try {
            HydeGenerator.HydeResult hydeResult = hydeGenerator.generate(
                    new HydeGenerator.HydeCommand(context.command().userQuestion())
            );
            if (hydeResult.hypotheticalAnswer() == null || hydeResult.hypotheticalAnswer().isBlank()) {
                drafts.add(RetrievalTaskDraft.skipped(
                        RetrievalTaskType.HYDE_DENSE,
                        context.command().userQuestion(),
                        "HyDE 返回为空"
                ));
                return drafts;
            }
            drafts.add(RetrievalTaskDraft.running(RetrievalTaskType.HYDE_DENSE, hydeResult.hypotheticalAnswer()));
            return drafts;
        } catch (Exception exception) {
            log.error("构建 HyDE 任务异常: questionLength={}",
                    context.command().userQuestion().length(), exception);
            drafts.add(RetrievalTaskDraft.failed(
                    RetrievalTaskType.HYDE_DENSE,
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
