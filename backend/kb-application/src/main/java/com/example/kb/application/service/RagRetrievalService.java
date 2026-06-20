package com.example.kb.application.service;

import com.example.kb.application.port.ConversationRetrievalTaskHitRepository;
import com.example.kb.application.port.ConversationRetrievalTaskRepository;
import com.example.kb.application.port.EmbeddingGenerator;
import com.example.kb.application.port.HydeGenerator;
import com.example.kb.application.port.KeywordIndexSearcher;
import com.example.kb.application.port.MultiQueryGenerator;
import com.example.kb.application.port.QueryRewriteGenerator;
import com.example.kb.application.port.VectorIndexSearcher;
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

    private final QueryRewriteGenerator queryRewriteGenerator;
    private final HydeGenerator hydeGenerator;
    private final MultiQueryGenerator multiQueryGenerator;
    private final EmbeddingGenerator embeddingGenerator;
    private final VectorIndexSearcher vectorIndexSearcher;
    private final KeywordIndexSearcher keywordIndexSearcher;
    private final ConversationRetrievalTaskRepository conversationRetrievalTaskRepository;
    private final ConversationRetrievalTaskHitRepository conversationRetrievalTaskHitRepository;
    private final RrfFusionService rrfFusionService;
    private final RagRetrievalProperties properties;
    private final ExecutorService retrievalExecutorService;

    public RagRetrievalService(
            QueryRewriteGenerator queryRewriteGenerator,
            HydeGenerator hydeGenerator,
            MultiQueryGenerator multiQueryGenerator,
            EmbeddingGenerator embeddingGenerator,
            VectorIndexSearcher vectorIndexSearcher,
            KeywordIndexSearcher keywordIndexSearcher,
            ConversationRetrievalTaskRepository conversationRetrievalTaskRepository,
            ConversationRetrievalTaskHitRepository conversationRetrievalTaskHitRepository,
            RrfFusionService rrfFusionService,
            RagRetrievalProperties properties,
            ExecutorService retrievalExecutorService
    ) {
        this.queryRewriteGenerator = queryRewriteGenerator;
        this.hydeGenerator = hydeGenerator;
        this.multiQueryGenerator = multiQueryGenerator;
        this.embeddingGenerator = embeddingGenerator;
        this.vectorIndexSearcher = vectorIndexSearcher;
        this.keywordIndexSearcher = keywordIndexSearcher;
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
        List<RetrievalTaskDraft> taskDrafts = buildTaskDrafts(command);
        List<CompletableFuture<RetrievalTaskReport>> futures = new ArrayList<>(taskDrafts.size());
        for (RetrievalTaskDraft taskDraft : taskDrafts) {
            if (taskDraft.status() == RetrievalTaskStatus.SKIPPED) {
                futures.add(CompletableFuture.completedFuture(RetrievalTaskReport.skipped(taskDraft)));
            } else if (taskDraft.status() == RetrievalTaskStatus.FAILED) {
                futures.add(CompletableFuture.completedFuture(
                        RetrievalTaskReport.failed(taskDraft, LocalDateTime.now(), LocalDateTime.now())
                ));
            } else if (taskDraft.taskType() == RetrievalTaskType.BM25) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> executeBm25Task(taskDraft, command.knowledgeBaseIds()),
                        retrievalExecutorService
                ));
            } else {
                futures.add(CompletableFuture.supplyAsync(
                        () -> executeDenseTask(taskDraft, command.knowledgeBaseIds()),
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

    private List<RetrievalTaskDraft> buildTaskDrafts(RetrievalCommand command) {
        log.info("构建检索任务入参: questionLength={}, intent={}",
                command.userQuestion().length(), command.queryIntent());
        List<RetrievalTaskDraft> drafts = new ArrayList<>();
        drafts.add(RetrievalTaskDraft.running(RetrievalTaskType.ORIGINAL_DENSE, command.userQuestion()));
        if (properties.isQueryRewriteEnabled()) {
            addRewriteTask(command, drafts);
        }
        if (properties.isHydeEnabled()) {
            addHydeTask(command, drafts);
        }
        if (properties.isMultiQueryEnabled()) {
            addMultiQueryTasks(command, drafts);
        }
        if (properties.isBm25Enabled()) {
            drafts.add(RetrievalTaskDraft.running(
                    RetrievalTaskType.BM25,
                    command.userQuestion()
            ));
        }
        log.info("构建检索任务出参: count={}", drafts.size());
        return drafts;
    }

    private void addRewriteTask(RetrievalCommand command, List<RetrievalTaskDraft> drafts) {
        try {
            QueryRewriteGenerator.RewriteResult rewriteResult = queryRewriteGenerator.rewrite(
                    new QueryRewriteGenerator.RewriteCommand(
                            command.userQuestion(),
                            command.queryIntent(),
                            command.recentMessages()
                    )
            );
            if (rewriteResult.rewrittenQuery().isBlank()
                    || rewriteResult.rewrittenQuery().equals(command.userQuestion())
                    || !Boolean.TRUE.equals(rewriteResult.changed())) {
                drafts.add(RetrievalTaskDraft.skipped(
                        RetrievalTaskType.REWRITE_DENSE,
                        command.userQuestion(),
                        "Query 改写未产生有效变化"
                ));
                return;
            }
            drafts.add(RetrievalTaskDraft.running(RetrievalTaskType.REWRITE_DENSE, rewriteResult.rewrittenQuery()));
        } catch (Exception exception) {
            log.error("构建 Query 改写任务异常: questionLength={}", command.userQuestion().length(), exception);
            drafts.add(RetrievalTaskDraft.failed(
                    RetrievalTaskType.REWRITE_DENSE,
                    command.userQuestion(),
                    exception.getMessage()
            ));
        }
    }

    private void addHydeTask(RetrievalCommand command, List<RetrievalTaskDraft> drafts) {
        try {
            HydeGenerator.HydeResult hydeResult = hydeGenerator.generate(
                    new HydeGenerator.HydeCommand(command.userQuestion())
            );
            if (hydeResult.hypotheticalAnswer() == null || hydeResult.hypotheticalAnswer().isBlank()) {
                drafts.add(RetrievalTaskDraft.skipped(
                        RetrievalTaskType.HYDE_DENSE,
                        command.userQuestion(),
                        "HyDE 返回为空"
                ));
                return;
            }
            drafts.add(RetrievalTaskDraft.running(RetrievalTaskType.HYDE_DENSE, hydeResult.hypotheticalAnswer()));
        } catch (Exception exception) {
            log.error("构建 HyDE 任务异常: questionLength={}", command.userQuestion().length(), exception);
            drafts.add(RetrievalTaskDraft.failed(
                    RetrievalTaskType.HYDE_DENSE,
                    command.userQuestion(),
                    exception.getMessage()
            ));
        }
    }

    private void addMultiQueryTasks(RetrievalCommand command, List<RetrievalTaskDraft> drafts) {
        try {
            MultiQueryGenerator.MultiQueryResult multiQueryResult = multiQueryGenerator.generate(
                    new MultiQueryGenerator.MultiQueryCommand(
                            command.userQuestion(),
                            properties.safeMultiQueryCount()
                    )
            );
            for (String query : multiQueryResult.queries()) {
                if (!query.equals(command.userQuestion())) {
                    drafts.add(RetrievalTaskDraft.running(RetrievalTaskType.MULTI_QUERY_DENSE, query));
                }
            }
        } catch (Exception exception) {
            log.error("构建 Multi Query 任务异常: questionLength={}", command.userQuestion().length(), exception);
            drafts.add(RetrievalTaskDraft.failed(
                    RetrievalTaskType.MULTI_QUERY_DENSE,
                    command.userQuestion(),
                    exception.getMessage()
            ));
        }
    }

    private RetrievalTaskReport executeDenseTask(RetrievalTaskDraft taskDraft, List<Long> knowledgeBaseIds) {
        LocalDateTime startedAt = LocalDateTime.now();
        log.info("执行 Dense 检索任务入参: taskType={}, queryLength={}, knowledgeBaseIds={}",
                taskDraft.taskType(), taskDraft.queryText().length(), knowledgeBaseIds);
        if (taskDraft.status() == RetrievalTaskStatus.FAILED) {
            return RetrievalTaskReport.failed(taskDraft, startedAt, LocalDateTime.now());
        }
        try {
            EmbeddingGenerator.GenerateEmbeddingsResult embeddingsResult = embeddingGenerator.generate(
                    new EmbeddingGenerator.GenerateEmbeddingsCommand(List.of(taskDraft.queryText()))
            );
            List<Float> queryVector = embeddingsResult.items().get(0).vector();
            VectorIndexSearcher.SearchResult searchResult = vectorIndexSearcher.search(
                    new VectorIndexSearcher.SearchCommand(knowledgeBaseIds, queryVector, properties.safeDenseTopK())
            );
            RetrievalTaskReport report = RetrievalTaskReport.success(
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
            return RetrievalTaskReport.failed(
                    RetrievalTaskDraft.failed(taskDraft.taskType(), taskDraft.queryText(), exception.getMessage()),
                    startedAt,
                    LocalDateTime.now()
            );
        }
    }

    private RetrievalTaskReport executeBm25Task(RetrievalTaskDraft taskDraft, List<Long> knowledgeBaseIds) {
        LocalDateTime startedAt = LocalDateTime.now();
        log.info("执行 BM25 检索任务入参: taskType={}, queryLength={}, knowledgeBaseIds={}",
                taskDraft.taskType(), taskDraft.queryText().length(), knowledgeBaseIds);
        try {
            KeywordIndexSearcher.KeywordSearchResult searchResult = keywordIndexSearcher.search(
                    new KeywordIndexSearcher.KeywordSearchCommand(
                            knowledgeBaseIds,
                            taskDraft.queryText(),
                            properties.safeBm25TopK()
                    )
            );
            List<VectorIndexSearcher.SearchHit> hits = searchResult.hits().stream()
                    .map(KeywordIndexSearcher.KeywordSearchHit::toVectorSearchHit)
                    .toList();
            RetrievalTaskReport report = RetrievalTaskReport.success(
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
            return RetrievalTaskReport.failed(
                    RetrievalTaskDraft.failed(taskDraft.taskType(), taskDraft.queryText(), exception.getMessage()),
                    startedAt,
                    LocalDateTime.now()
            );
        }
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

    private record RetrievalTaskDraft(
            RetrievalTaskType taskType,
            String queryText,
            RetrievalTaskStatus status,
            String errorMessage
    ) {
        private static RetrievalTaskDraft running(RetrievalTaskType taskType, String queryText) {
            return new RetrievalTaskDraft(taskType, queryText, RetrievalTaskStatus.RUNNING, null);
        }

        private static RetrievalTaskDraft failed(RetrievalTaskType taskType, String queryText, String errorMessage) {
            return new RetrievalTaskDraft(taskType, queryText, RetrievalTaskStatus.FAILED, errorMessage);
        }

        private static RetrievalTaskDraft skipped(RetrievalTaskType taskType, String queryText, String errorMessage) {
            return new RetrievalTaskDraft(taskType, queryText, RetrievalTaskStatus.SKIPPED, errorMessage);
        }
    }
}
