package com.example.kb.application.service;

import com.example.kb.application.port.ConversationRetrievalTaskHitRepository;
import com.example.kb.application.port.ConversationRetrievalTaskRepository;
import com.example.kb.application.port.VectorIndexSearcher;
import com.example.kb.application.service.retrieval.RetrievalTaskDraft;
import com.example.kb.application.service.retrieval.RetrievalTaskExecutionContext;
import com.example.kb.application.service.retrieval.RetrievalTaskExecutor;
import com.example.kb.domain.model.ConversationMessage;
import com.example.kb.domain.model.ConversationRetrievalTask;
import com.example.kb.domain.model.ConversationRetrievalTaskHit;
import com.example.kb.domain.model.QueryIntent;
import com.example.kb.domain.model.RetrievalTaskStatus;
import com.example.kb.domain.model.RetrievalTaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);

    private final List<RetrievalTaskExecutor> retrievalTaskExecutors;
    private final ConversationRetrievalTaskRepository conversationRetrievalTaskRepository;
    private final ConversationRetrievalTaskHitRepository conversationRetrievalTaskHitRepository;
    private final RrfFusionService rrfFusionService;
    private final RagRetrievalProperties properties;
    private final ExecutorService retrievalExecutorService;

    public RagRetrievalService(
            List<RetrievalTaskExecutor> retrievalTaskExecutors,
            ConversationRetrievalTaskRepository conversationRetrievalTaskRepository,
            ConversationRetrievalTaskHitRepository conversationRetrievalTaskHitRepository,
            RrfFusionService rrfFusionService,
            RagRetrievalProperties properties,
            ExecutorService retrievalExecutorService
    ) {
        this.retrievalTaskExecutors = retrievalTaskExecutors;
        this.conversationRetrievalTaskRepository = conversationRetrievalTaskRepository;
        this.conversationRetrievalTaskHitRepository = conversationRetrievalTaskHitRepository;
        this.rrfFusionService = rrfFusionService;
        this.properties = properties;
        this.retrievalExecutorService = retrievalExecutorService;
    }

    public RetrievalResult retrieve(RetrievalCommand command) {
        log.info("RAG 检索增强入参: questionLength={}, knowledgeBaseIds={}, historyCount={}",
                command.userQuestion().length(), command.knowledgeBaseIds(), command.recentMessages().size());
        if (command.knowledgeBaseIds().isEmpty()) {
            log.warn("RAG 检索增强分支: knowledgeBaseIds 为空");
            return new RetrievalResult(List.of(), List.of());
        }
        RetrievalTaskExecutionContext context = new RetrievalTaskExecutionContext(
                command,
                properties,
                command.knowledgeBaseIds()
        );
        List<RetrievalTaskExecution> executions = buildTaskExecutions(context);
        List<CompletableFuture<RetrievalTaskReport>> futures = new ArrayList<>(executions.size());
        for (RetrievalTaskExecution execution : executions) {
            RetrievalTaskDraft taskDraft = execution.taskDraft();
            if (taskDraft.status() == RetrievalTaskStatus.SKIPPED) {
                futures.add(CompletableFuture.completedFuture(RetrievalTaskReport.skipped(taskDraft)));
            } else if (taskDraft.status() == RetrievalTaskStatus.FAILED) {
                futures.add(CompletableFuture.completedFuture(
                        RetrievalTaskReport.failed(taskDraft, LocalDateTime.now(), LocalDateTime.now())
                ));
            } else {
                futures.add(CompletableFuture.supplyAsync(
                        () -> execution.executor().execute(taskDraft, context),
                        retrievalExecutorService
                ));
            }
        }
        List<RetrievalTaskReport> reports = new ArrayList<>(futures.size());
        for (CompletableFuture<RetrievalTaskReport> future : futures) {
            reports.add(future.join());
        }
        List<VectorIndexSearcher.SearchHit> fusedHits = rrfFusionService.fuse(
                reports,
                properties.safeFusionTopK(),
                properties.safeRrfK()
        );
        log.info("RAG 检索增强出参: taskCount={}, fusedHitCount={}", reports.size(), fusedHits.size());
        return new RetrievalResult(fusedHits, reports);
    }

    private List<RetrievalTaskExecution> buildTaskExecutions(RetrievalTaskExecutionContext context) {
        log.info("构建检索任务入参: questionLength={}, intent={}, executorCount={}",
                context.command().userQuestion().length(), context.command().queryIntent(), retrievalTaskExecutors.size());
        List<RetrievalTaskExecution> executions = new ArrayList<>();
        for (RetrievalTaskExecutor executor : retrievalTaskExecutors) {
            List<RetrievalTaskDraft> taskDrafts = executor.buildTaskDrafts(context);
            for (RetrievalTaskDraft taskDraft : taskDrafts) {
                executions.add(new RetrievalTaskExecution(executor, taskDraft));
            }
        }
        log.info("构建检索任务出参: count={}", executions.size());
        return executions;
    }

    public void saveTaskReports(Long conversationRetrievalId, List<RetrievalTaskReport> taskReports) {
        log.info("保存检索增强任务报告入参: retrievalId={}, taskCount={}", conversationRetrievalId, taskReports.size());
        if (taskReports.isEmpty()) {
            log.info("保存检索增强任务报告分支: 空列表");
            return;
        }
        for (RetrievalTaskReport taskReport : taskReports) {
            ConversationRetrievalTask savedTask = conversationRetrievalTaskRepository.save(new ConversationRetrievalTask(
                    null,
                    conversationRetrievalId,
                    taskReport.taskType(),
                    taskReport.queryText(),
                    taskReport.status(),
                    truncate(taskReport.errorMessage(), 2048),
                    taskReport.startedAt(),
                    taskReport.finishedAt(),
                    null,
                    null
            ));
            saveTaskHits(savedTask.id(), taskReport.hits());
        }
        log.info("保存检索增强任务报告出参: retrievalId={}, taskCount={}", conversationRetrievalId, taskReports.size());
    }

    private void saveTaskHits(Long retrievalTaskId, List<VectorIndexSearcher.SearchHit> hits) {
        if (hits.isEmpty()) {
            log.info("保存检索任务命中分支: 空列表, retrievalTaskId={}", retrievalTaskId);
            return;
        }
        List<ConversationRetrievalTaskHit> taskHits = new ArrayList<>(hits.size());
        LocalDateTime now = LocalDateTime.now();
        int rankNo = 1;
        for (VectorIndexSearcher.SearchHit hit : hits) {
            taskHits.add(new ConversationRetrievalTaskHit(
                    null,
                    retrievalTaskId,
                    hit.knowledgeBaseId(),
                    hit.fileId(),
                    hit.chunkId(),
                    hit.chunkIndex(),
                    hit.score(),
                    rankNo,
                    now
            ));
            rankNo++;
        }
        conversationRetrievalTaskHitRepository.saveBatch(taskHits);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record RetrievalCommand(
            String userQuestion,
            QueryIntent queryIntent,
            List<Long> knowledgeBaseIds,
            List<ConversationMessage> recentMessages
    ) {
    }

    public record RetrievalResult(
            List<VectorIndexSearcher.SearchHit> fusedHits,
            List<RetrievalTaskReport> taskReports
    ) {
    }

    public record RetrievalTaskReport(
            RetrievalTaskType taskType,
            String queryText,
            RetrievalTaskStatus status,
            String errorMessage,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            List<VectorIndexSearcher.SearchHit> hits
    ) {
        public static RetrievalTaskReport success(
                RetrievalTaskDraft taskDraft,
                List<VectorIndexSearcher.SearchHit> hits,
                LocalDateTime startedAt,
                LocalDateTime finishedAt
        ) {
            return new RetrievalTaskReport(
                    taskDraft.taskType(),
                    taskDraft.queryText(),
                    RetrievalTaskStatus.SUCCESS,
                    null,
                    startedAt,
                    finishedAt,
                    hits
            );
        }

        public static RetrievalTaskReport failed(
                RetrievalTaskDraft taskDraft,
                LocalDateTime startedAt,
                LocalDateTime finishedAt
        ) {
            return new RetrievalTaskReport(
                    taskDraft.taskType(),
                    taskDraft.queryText(),
                    RetrievalTaskStatus.FAILED,
                    taskDraft.errorMessage(),
                    startedAt,
                    finishedAt,
                    List.of()
            );
        }

        public static RetrievalTaskReport success(
                RetrievalTaskType taskType,
                String queryText,
                List<VectorIndexSearcher.SearchHit> hits,
                LocalDateTime startedAt,
                LocalDateTime finishedAt
        ) {
            return new RetrievalTaskReport(
                    taskType,
                    queryText,
                    RetrievalTaskStatus.SUCCESS,
                    null,
                    startedAt,
                    finishedAt,
                    hits
            );
        }

        public static RetrievalTaskReport failed(
                RetrievalTaskType taskType,
                String queryText,
                String errorMessage,
                LocalDateTime startedAt,
                LocalDateTime finishedAt
        ) {
            return new RetrievalTaskReport(
                    taskType,
                    queryText,
                    RetrievalTaskStatus.FAILED,
                    errorMessage,
                    startedAt,
                    finishedAt,
                    List.of()
            );
        }

        public static RetrievalTaskReport skipped(RetrievalTaskDraft taskDraft) {
            LocalDateTime now = LocalDateTime.now();
            return new RetrievalTaskReport(
                    taskDraft.taskType(),
                    taskDraft.queryText(),
                    RetrievalTaskStatus.SKIPPED,
                    taskDraft.errorMessage(),
                    now,
                    now,
                    List.of()
            );
        }

        public boolean success() {
            return status == RetrievalTaskStatus.SUCCESS;
        }
    }

    private record RetrievalTaskExecution(
            RetrievalTaskExecutor executor,
            RetrievalTaskDraft taskDraft
    ) {
    }
}
