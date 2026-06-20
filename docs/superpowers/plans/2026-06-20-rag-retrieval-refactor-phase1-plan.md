# RAG 检索增强重构一期 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `RagRetrievalService` 中的多路检索任务构建和执行逻辑拆成可插拔的任务执行器，保持现有 RAG 查询行为不变。

**Architecture:** 第一期只重构检索增强内部结构，不改 `RagChatService` 调用方式，不改数据库表，不改前端。新增 `RetrievalTaskExecutor` 策略接口和多个任务实现，由 `RagRetrievalService` 统一调度、并发执行、RRF 融合和保存任务报告。

**Tech Stack:** Java、Spring Boot、CompletableFuture、ExecutorService、Milvus、DashScope Embedding、MyBatis XML、MySQL。

---

## 一期范围

本期只重构检索增强链路：

```text
RagChatService
  -> RagRetrievalService.retrieve(...)
      -> RetrievalTaskExecutor 列表
      -> 并发执行
      -> RRF 融合
      -> 返回 RetrievalResult
```

保持不变：

- `RagRetrievalService.retrieve(...)` 方法签名不变。
- `RagRetrievalService.RetrievalResult` 不变。
- `RagRetrievalService.RetrievalTaskReport` 不变。
- `RagChatService` 调用方式不变。
- `conversation_retrieval_task` 保存逻辑不变。
- `conversation_retrieval_task_hit` 保存逻辑不变。
- Rerank 逻辑不迁入本期。
- 不新增 SQL 表。
- 不写 Java 单元测试。

## 目标结构

新增包：

```text
backend/kb-application/src/main/java/com/example/kb/application/service/retrieval/
```

建议文件：

```text
retrieval/
  RetrievalTaskExecutor.java
  RetrievalTaskExecutionContext.java
  RetrievalTaskDraft.java
  AbstractDenseRetrievalTaskExecutor.java
  OriginalDenseRetrievalTaskExecutor.java
  RewriteDenseRetrievalTaskExecutor.java
  HydeDenseRetrievalTaskExecutor.java
  MultiQueryDenseRetrievalTaskExecutor.java
  Bm25RetrievalTaskExecutor.java
```

`RagRetrievalService` 保留职责：

- 校验 `knowledgeBaseIds`。
- 构造 `RetrievalTaskExecutionContext`。
- 遍历 `RetrievalTaskExecutor`。
- 并发执行任务。
- 收集 `RetrievalTaskReport`。
- 调用 `RrfFusionService`。
- 保存任务报告。

每个 executor 负责：

- 判断自己是否启用。
- 生成一个或多个 `RetrievalTaskDraft`。
- 执行检索并返回 `RetrievalTaskReport`。
- 记录本任务内部日志。
- 失败时只返回失败报告，不抛出影响主流程的异常。

## 行为保持清单

重构后必须保持：

- 原始 query 永远执行 `ORIGINAL_DENSE`。
- Query 改写开启时，只有改写有效才执行 `REWRITE_DENSE`。
- Query 改写失败时保存 `REWRITE_DENSE` failed report。
- HyDE 开启时，生成非空才执行 `HYDE_DENSE`。
- HyDE 失败时保存 `HYDE_DENSE` failed report。
- 多 Query 开启时，每个不同于原问题的 query 执行 `MULTI_QUERY_DENSE`。
- 多 Query 失败时保存 `MULTI_QUERY_DENSE` failed report。
- BM25 开启时执行 `BM25`。
- Dense 检索异常时只影响当前 dense task。
- BM25 检索异常时只影响当前 BM25 task。
- 所有 task 仍然通过线程池并发执行。
- RRF 输入仍然是所有 `RetrievalTaskReport`。
- RRF 参数仍使用 `fusionTopK` 和 `rrfK`。

## Task 1: 新增 RetrievalTaskDraft

**Files:**
- Create: `backend/kb-application/src/main/java/com/example/kb/application/service/retrieval/RetrievalTaskDraft.java`
- Modify: `backend/kb-application/src/main/java/com/example/kb/application/service/RagRetrievalService.java`

- [ ] **Step 1: 创建 RetrievalTaskDraft**

新增文件：

```java
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
```

- [ ] **Step 2: 修改 RagRetrievalService 引用外部 RetrievalTaskDraft**

在 `RagRetrievalService` 中新增 import：

```java
import com.example.kb.application.service.retrieval.RetrievalTaskDraft;
```

删除 `RagRetrievalService` 底部的内部 `private record RetrievalTaskDraft`。

- [ ] **Step 3: 编译验证**

Run:

```bash
cd /Users/tangjie/javaai/agent/backend
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected: 编译通过。

## Task 2: 新增 RetrievalTaskExecutionContext

**Files:**
- Create: `backend/kb-application/src/main/java/com/example/kb/application/service/retrieval/RetrievalTaskExecutionContext.java`

- [ ] **Step 1: 创建上下文对象**

新增文件：

```java
package com.example.kb.application.service.retrieval;

import com.example.kb.application.service.RagRetrievalProperties;
import com.example.kb.application.service.RagRetrievalService;

import java.util.List;

public record RetrievalTaskExecutionContext(
        RagRetrievalService.RetrievalCommand command,
        RagRetrievalProperties properties,
        List<Long> knowledgeBaseIds
) {
}
```

- [ ] **Step 2: 编译验证**

Run:

```bash
cd /Users/tangjie/javaai/agent/backend
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected: 编译通过。

## Task 3: 新增 RetrievalTaskExecutor 接口

**Files:**
- Create: `backend/kb-application/src/main/java/com/example/kb/application/service/retrieval/RetrievalTaskExecutor.java`

- [ ] **Step 1: 创建接口**

新增文件：

```java
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
```

- [ ] **Step 2: 编译验证**

Run:

```bash
cd /Users/tangjie/javaai/agent/backend
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected: 编译通过。

## Task 4: 抽取 Dense 检索基类

**Files:**
- Create: `backend/kb-application/src/main/java/com/example/kb/application/service/retrieval/AbstractDenseRetrievalTaskExecutor.java`

- [ ] **Step 1: 创建 Dense 抽象基类**

新增文件：

```java
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
```

- [ ] **Step 2: 编译验证**

Run:

```bash
cd /Users/tangjie/javaai/agent/backend
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected: 编译通过。

## Task 5: 实现 OriginalDenseRetrievalTaskExecutor

**Files:**
- Create: `backend/kb-application/src/main/java/com/example/kb/application/service/retrieval/OriginalDenseRetrievalTaskExecutor.java`

- [ ] **Step 1: 创建原始 Query Dense executor**

新增文件：

```java
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
```

- [ ] **Step 2: 编译验证**

Run:

```bash
cd /Users/tangjie/javaai/agent/backend
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected: 编译通过。

## Task 6: 实现 RewriteDenseRetrievalTaskExecutor

**Files:**
- Create: `backend/kb-application/src/main/java/com/example/kb/application/service/retrieval/RewriteDenseRetrievalTaskExecutor.java`

- [ ] **Step 1: 创建 Query 改写 executor**

新增文件：

```java
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
```

- [ ] **Step 2: 编译验证**

Run:

```bash
cd /Users/tangjie/javaai/agent/backend
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected: 编译通过。

## Task 7: 实现 HydeDenseRetrievalTaskExecutor

**Files:**
- Create: `backend/kb-application/src/main/java/com/example/kb/application/service/retrieval/HydeDenseRetrievalTaskExecutor.java`

- [ ] **Step 1: 创建 HyDE executor**

新增文件：

```java
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
```

- [ ] **Step 2: 编译验证**

Run:

```bash
cd /Users/tangjie/javaai/agent/backend
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected: 编译通过。

## Task 8: 实现 MultiQueryDenseRetrievalTaskExecutor

**Files:**
- Create: `backend/kb-application/src/main/java/com/example/kb/application/service/retrieval/MultiQueryDenseRetrievalTaskExecutor.java`

- [ ] **Step 1: 创建多 Query executor**

新增文件：

```java
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
```

- [ ] **Step 2: 编译验证**

Run:

```bash
cd /Users/tangjie/javaai/agent/backend
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected: 编译通过。

## Task 9: 实现 Bm25RetrievalTaskExecutor

**Files:**
- Create: `backend/kb-application/src/main/java/com/example/kb/application/service/retrieval/Bm25RetrievalTaskExecutor.java`

- [ ] **Step 1: 创建 BM25 executor**

新增文件：

```java
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
```

- [ ] **Step 2: 编译验证**

Run:

```bash
cd /Users/tangjie/javaai/agent/backend
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected: 编译通过。

## Task 10: 改造 RagRetrievalService 使用 executor 列表

**Files:**
- Modify: `backend/kb-application/src/main/java/com/example/kb/application/service/RagRetrievalService.java`

- [ ] **Step 1: 调整字段和构造器**

删除以下字段：

```java
private final QueryRewriteGenerator queryRewriteGenerator;
private final HydeGenerator hydeGenerator;
private final MultiQueryGenerator multiQueryGenerator;
private final EmbeddingGenerator embeddingGenerator;
private final VectorIndexSearcher vectorIndexSearcher;
private final KeywordIndexSearcher keywordIndexSearcher;
```

新增字段：

```java
private final List<RetrievalTaskExecutor> retrievalTaskExecutors;
```

构造器改为接收：

```java
List<RetrievalTaskExecutor> retrievalTaskExecutors,
ConversationRetrievalTaskRepository conversationRetrievalTaskRepository,
ConversationRetrievalTaskHitRepository conversationRetrievalTaskHitRepository,
RrfFusionService rrfFusionService,
RagRetrievalProperties properties,
ExecutorService retrievalExecutorService
```

- [ ] **Step 2: 改造 retrieve 方法**

将构建任务和执行任务改成：

```java
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
```

- [ ] **Step 3: 新增 buildTaskExecutions 方法**

新增：

```java
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
```

- [ ] **Step 4: 删除旧私有方法**

删除：

```java
buildTaskDrafts(...)
addRewriteTask(...)
addHydeTask(...)
addMultiQueryTasks(...)
executeDenseTask(...)
executeBm25Task(...)
```

- [ ] **Step 5: 新增内部 RetrievalTaskExecution record**

新增：

```java
private record RetrievalTaskExecution(
        RetrievalTaskExecutor executor,
        RetrievalTaskDraft taskDraft
) {
}
```

- [ ] **Step 6: 编译验证**

Run:

```bash
cd /Users/tangjie/javaai/agent/backend
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected: 编译通过。

## Task 11: 注册 executor Bean 列表

**Files:**
- Modify: `backend/kb-bootstrap/src/main/java/com/example/kb/bootstrap/ApplicationServiceConfiguration.java`

- [ ] **Step 1: 增加 imports**

新增：

```java
import com.example.kb.application.service.retrieval.Bm25RetrievalTaskExecutor;
import com.example.kb.application.service.retrieval.HydeDenseRetrievalTaskExecutor;
import com.example.kb.application.service.retrieval.MultiQueryDenseRetrievalTaskExecutor;
import com.example.kb.application.service.retrieval.OriginalDenseRetrievalTaskExecutor;
import com.example.kb.application.service.retrieval.RetrievalTaskExecutor;
import com.example.kb.application.service.retrieval.RewriteDenseRetrievalTaskExecutor;
```

- [ ] **Step 2: 新增 executor Bean 方法**

新增：

```java
@Bean
public List<RetrievalTaskExecutor> retrievalTaskExecutors(
        QueryRewriteGenerator queryRewriteGenerator,
        HydeGenerator hydeGenerator,
        MultiQueryGenerator multiQueryGenerator,
        EmbeddingGenerator embeddingGenerator,
        VectorIndexSearcher vectorIndexSearcher,
        KeywordIndexSearcher keywordIndexSearcher
) {
    List<RetrievalTaskExecutor> executors = new ArrayList<>();
    executors.add(new OriginalDenseRetrievalTaskExecutor(embeddingGenerator, vectorIndexSearcher));
    executors.add(new RewriteDenseRetrievalTaskExecutor(queryRewriteGenerator, embeddingGenerator, vectorIndexSearcher));
    executors.add(new HydeDenseRetrievalTaskExecutor(hydeGenerator, embeddingGenerator, vectorIndexSearcher));
    executors.add(new MultiQueryDenseRetrievalTaskExecutor(multiQueryGenerator, embeddingGenerator, vectorIndexSearcher));
    executors.add(new Bm25RetrievalTaskExecutor(keywordIndexSearcher));
    return executors;
}
```

需要新增 Java import：

```java
import java.util.ArrayList;
import java.util.List;
```

- [ ] **Step 3: 修改 ragRetrievalService Bean 参数**

将原来的多个生成器和 searcher 参数替换为：

```java
List<RetrievalTaskExecutor> retrievalTaskExecutors,
ConversationRetrievalTaskRepository conversationRetrievalTaskRepository,
ConversationRetrievalTaskHitRepository conversationRetrievalTaskHitRepository,
RrfFusionService rrfFusionService,
RagRetrievalProperties ragRetrievalProperties,
@Qualifier("ragRetrievalExecutorService") ExecutorService ragRetrievalExecutorService
```

构造调用改为：

```java
return new RagRetrievalService(
        retrievalTaskExecutors,
        conversationRetrievalTaskRepository,
        conversationRetrievalTaskHitRepository,
        rrfFusionService,
        ragRetrievalProperties,
        ragRetrievalExecutorService
);
```

- [ ] **Step 4: 编译验证**

Run:

```bash
cd /Users/tangjie/javaai/agent/backend
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected: 编译通过。

## Task 12: 行为验证

**Files:**
- No file changes.

- [ ] **Step 1: 编译验证**

Run:

```bash
cd /Users/tangjie/javaai/agent/backend
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected: 编译通过。

- [ ] **Step 2: Java var 扫描**

Run:

```bash
cd /Users/tangjie/javaai/agent
rg "\bvar\b" backend --glob "*.java"
```

Expected: 无输出。

- [ ] **Step 3: Mapper 注解 SQL 扫描**

Run:

```bash
cd /Users/tangjie/javaai/agent
rg "@Insert|@Update|@Delete|@Select" backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/mapper
```

Expected: 无输出。

- [ ] **Step 4: 本地功能验证**

用户启动后端并发起一次 RAG 查询。

Expected:

- `conversation_retrieval_task` 仍然记录原有任务类型。
- 开启 BM25 时仍然有 `BM25` task。
- 开启 Rerank 时仍然有 `RERANK` task。
- 回答正常生成。
- 日志中出现各 executor 的中文入参和出参日志。

验证 SQL：

```sql
SELECT task_type, status, COUNT(*) AS cnt
FROM conversation_retrieval_task
GROUP BY task_type, status
ORDER BY task_type, status;
```

## 自检清单

- [ ] 没有修改外部 API。
- [ ] 没有修改前端。
- [ ] 没有新增数据库表。
- [ ] 没有修改 Mapper XML。
- [ ] 没有写 Java 单元测试。
- [ ] `RagRetrievalService` 不再直接依赖 QueryRewrite、HyDE、MultiQuery、Embedding、BM25 searcher。
- [ ] 新增检索任务时，只需要新增一个 `RetrievalTaskExecutor` 并注册到列表。
- [ ] 编译通过。
- [ ] Java `var` 扫描无输出。
