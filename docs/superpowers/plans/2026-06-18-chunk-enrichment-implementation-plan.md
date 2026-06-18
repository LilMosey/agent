# Chunk Enrichment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 chunk 后接入 AgentScope Java 调用真实 LLM，为每个 chunk 生成摘要、模拟问题和 embedding_text，并保存到 MySQL 与 MinIO。

**Architecture:** 新增 `ChunkEnrichment` 领域模型、应用层端口、应用服务、MyBatis 仓储、MinIO 增强文本存储和 AgentScope LLM 适配器。`DocumentIndexPipeline` 在 `DocumentChunkService.rebuildChunks` 后调用 enrichment 服务；enrichment 失败不影响文件 `READY`，失败原因记录在 enrichment 表。

**Tech Stack:** Java 21、Spring Boot 3.5、MyBatis-Plus、MinIO、MySQL 8、AgentScope Java、Jackson。

---

## 0. 关键决策

1. 第一版真实接入 AgentScope Java 调用 LLM。
2. AgentScope 只作为模型调用框架，不做复杂 Agent 编排。
3. enrichment 失败时文件仍然可以进入 `READY`。
4. enrichment 失败原因记录在 `knowledge_file_chunk_enrichment.error_message`。
5. embedding 阶段后续如果发现 enrichment 失败，可以回退使用原始 chunk 正文。
6. Java 代码不使用 `var`。
7. 本计划不写 Java 单元测试。

## 1. 文件结构

### 新增文件

领域层：

- `backend/kb-domain/src/main/java/com/example/kb/domain/model/ChunkEnrichment.java`
- `backend/kb-domain/src/main/java/com/example/kb/domain/model/ChunkEnrichmentQuestion.java`
- `backend/kb-domain/src/main/java/com/example/kb/domain/model/EnrichmentStatus.java`
- `backend/kb-domain/src/main/java/com/example/kb/domain/model/EnrichmentStrategy.java`

应用层端口：

- `backend/kb-application/src/main/java/com/example/kb/application/port/ChunkContentStorage.java`
- `backend/kb-application/src/main/java/com/example/kb/application/port/ChunkEnrichmentGenerator.java`
- `backend/kb-application/src/main/java/com/example/kb/application/port/ChunkEnrichmentObjectStorage.java`
- `backend/kb-application/src/main/java/com/example/kb/application/port/ChunkEnrichmentRepository.java`

应用层服务：

- `backend/kb-application/src/main/java/com/example/kb/application/service/ChunkEnrichmentService.java`

基础设施层：

- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/enrichment/AgentScopeChunkEnrichmentGenerator.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/enrichment/ChunkEnrichmentProperties.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/enrichment/ChunkEnrichmentPromptBuilder.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/enrichment/LlmChunkEnrichmentResponse.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/enrichment/MockChunkEnrichmentGenerator.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/entity/ChunkEnrichmentEntity.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/mapper/ChunkEnrichmentMapper.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisChunkEnrichmentRepository.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/storage/MinioChunkContentStorage.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/storage/MinioChunkEnrichmentObjectStorage.java`

### 修改文件

- `backend/pom.xml`
- `backend/kb-infrastructure/pom.xml`
- `backend/kb-bootstrap/src/main/resources/application.yml`
- `backend/kb-bootstrap/src/main/java/com/example/kb/bootstrap/ApplicationServiceConfiguration.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/index/DocumentIndexPipeline.java`
- `backend/kb-application/src/main/java/com/example/kb/application/service/KnowledgeFileService.java`
- `backend/kb-infrastructure/src/main/resources/db/schema.sql`

---

## 2. Task 1：确认并引入 AgentScope Java 依赖

**Files:**

- Modify: `backend/pom.xml`
- Modify: `backend/kb-infrastructure/pom.xml`

- [ ] **Step 1: 使用已确认的官方 Maven 坐标**

AgentScope Java Maven 坐标已经确认：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

版本：

```text
2.0.0-RC3
```

- [ ] **Step 2: 在父 pom 增加版本属性**

在 `backend/pom.xml` 的 `<properties>` 中增加 AgentScope 版本属性：

```xml
<agentscope.version>2.0.0-RC3</agentscope.version>
```

- [ ] **Step 3: 在 infrastructure 模块引入依赖**

在 `backend/kb-infrastructure/pom.xml` 增加：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

- [ ] **Step 4: 本地编译验证**

用户本地执行：

```bash
cd /Users/tangjie/javaai/agent/backend
mvn -q -DskipTests compile
```

期望：

```text
编译成功
```

---

## 3. Task 2：新增数据库表

**Files:**

- Modify: `backend/kb-infrastructure/src/main/resources/db/schema.sql`

- [ ] **Step 1: 在 schema.sql 增加 enrichment 表**

追加：

```sql
CREATE TABLE IF NOT EXISTS knowledge_file_chunk_enrichment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    chunk_id BIGINT NOT NULL,
    enrichment_strategy VARCHAR(64) NOT NULL,
    summary VARCHAR(1024) NULL,
    questions_json JSON NULL,
    embedding_text_bucket VARCHAR(255) NOT NULL,
    embedding_text_object_key VARCHAR(1024) NOT NULL,
    llm_provider VARCHAR(64) NULL,
    llm_model VARCHAR(128) NULL,
    prompt_version VARCHAR(64) NULL,
    status VARCHAR(64) NOT NULL,
    error_message VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_enrichment_chunk_strategy (chunk_id, enrichment_strategy),
    KEY idx_enrichment_file_id (file_id),
    KEY idx_enrichment_kb_file (knowledge_base_id, file_id),
    KEY idx_enrichment_status (status)
);
```

- [ ] **Step 2: 给用户提供 DBeaver 执行脚本**

用户本地执行同一段 SQL。验证：

```sql
SHOW TABLES LIKE 'knowledge_file_chunk_enrichment';
```

期望：

```text
能查到 knowledge_file_chunk_enrichment
```

---

## 4. Task 3：新增领域模型

**Files:**

- Create: `backend/kb-domain/src/main/java/com/example/kb/domain/model/EnrichmentStrategy.java`
- Create: `backend/kb-domain/src/main/java/com/example/kb/domain/model/EnrichmentStatus.java`
- Create: `backend/kb-domain/src/main/java/com/example/kb/domain/model/ChunkEnrichmentQuestion.java`
- Create: `backend/kb-domain/src/main/java/com/example/kb/domain/model/ChunkEnrichment.java`

- [ ] **Step 1: 新增 EnrichmentStrategy**

```java
package com.example.kb.domain.model;

public enum EnrichmentStrategy {
    HYBRID_TEXT("增强文本");

    private final String logName;

    EnrichmentStrategy(String logName) {
        this.logName = logName;
    }

    public String logName() {
        return logName;
    }
}
```

- [ ] **Step 2: 新增 EnrichmentStatus**

```java
package com.example.kb.domain.model;

public enum EnrichmentStatus {
    PENDING,
    RUNNING,
    READY,
    FAILED
}
```

- [ ] **Step 3: 新增 ChunkEnrichmentQuestion**

```java
package com.example.kb.domain.model;

public record ChunkEnrichmentQuestion(
        String question,
        String type
) {
}
```

- [ ] **Step 4: 新增 ChunkEnrichment**

```java
package com.example.kb.domain.model;

import java.time.LocalDateTime;

public record ChunkEnrichment(
        Long id,
        Long knowledgeBaseId,
        Long fileId,
        Long chunkId,
        EnrichmentStrategy enrichmentStrategy,
        String summary,
        String questionsJson,
        String embeddingTextBucket,
        String embeddingTextObjectKey,
        String llmProvider,
        String llmModel,
        String promptVersion,
        EnrichmentStatus status,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
```

---

## 5. Task 4：新增应用层端口

**Files:**

- Create: `backend/kb-application/src/main/java/com/example/kb/application/port/ChunkContentStorage.java`
- Create: `backend/kb-application/src/main/java/com/example/kb/application/port/ChunkEnrichmentObjectStorage.java`
- Create: `backend/kb-application/src/main/java/com/example/kb/application/port/ChunkEnrichmentRepository.java`
- Create: `backend/kb-application/src/main/java/com/example/kb/application/port/ChunkEnrichmentGenerator.java`

- [ ] **Step 1: 新增 ChunkContentStorage**

```java
package com.example.kb.application.port;

public interface ChunkContentStorage {

    String getChunkContent(String bucket, String objectKey);
}
```

- [ ] **Step 2: 新增 ChunkEnrichmentObjectStorage**

```java
package com.example.kb.application.port;

public interface ChunkEnrichmentObjectStorage {

    StoredEnrichmentObject putEmbeddingText(PutEmbeddingTextCommand command);

    void deleteEnrichmentsByFile(Long knowledgeBaseId, Long fileId);

    record PutEmbeddingTextCommand(
            Long knowledgeBaseId,
            Long fileId,
            Long chunkId,
            String content
    ) {
    }

    record StoredEnrichmentObject(
            String bucket,
            String objectKey
    ) {
    }
}
```

- [ ] **Step 3: 新增 ChunkEnrichmentRepository**

```java
package com.example.kb.application.port;

import com.example.kb.domain.model.ChunkEnrichment;

public interface ChunkEnrichmentRepository {

    void deleteByFileId(Long fileId);

    ChunkEnrichment save(ChunkEnrichment chunkEnrichment);
}
```

- [ ] **Step 4: 新增 ChunkEnrichmentGenerator**

```java
package com.example.kb.application.port;

import com.example.kb.domain.model.ChunkEnrichmentQuestion;

import java.util.List;

public interface ChunkEnrichmentGenerator {

    GenerateResult generate(GenerateCommand command);

    record GenerateCommand(
            Long knowledgeBaseId,
            Long fileId,
            Long chunkId,
            String filename,
            String titlePath,
            String chunkContent
    ) {
    }

    record GenerateResult(
            String summary,
            List<ChunkEnrichmentQuestion> questions,
            String llmProvider,
            String llmModel,
            String promptVersion
    ) {
    }
}
```

---

## 6. Task 5：实现 MinIO chunk 读取和 enrichment 文本存储

**Files:**

- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/storage/MinioChunkContentStorage.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/storage/MinioChunkEnrichmentObjectStorage.java`

- [ ] **Step 1: 新增 MinioChunkContentStorage**

核心逻辑：

```java
package com.example.kb.infrastructure.storage;

import com.example.kb.application.port.ChunkContentStorage;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Component
public class MinioChunkContentStorage implements ChunkContentStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioChunkContentStorage.class);

    private final MinioProperties minioProperties;
    private final MinioClient minioClient;

    public MinioChunkContentStorage(MinioProperties minioProperties) {
        this.minioProperties = minioProperties;
        this.minioClient = MinioClient.builder()
                .endpoint(minioProperties.endpoint())
                .credentials(minioProperties.accessKey(), minioProperties.secretKey())
                .build();
    }

    @Override
    public String getChunkContent(String bucket, String objectKey) {
        log.info("读取 chunk 正文入参: bucket={}, objectKey={}", bucket, objectKey);
        try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            log.info("读取 chunk 正文出参: bucket={}, objectKey={}, length={}", bucket, objectKey, content.length());
            return content;
        } catch (Exception exception) {
            log.error("读取 chunk 正文异常: bucket={}, objectKey={}", bucket, objectKey, exception);
            throw new IllegalStateException("读取 chunk 正文失败。", exception);
        }
    }
}
```

- [ ] **Step 2: 新增 MinioChunkEnrichmentObjectStorage**

实现规则：

```text
putEmbeddingText -> chunk-enrichments/{knowledgeBaseId}/{fileId}/{chunkId}/embedding-text.txt
deleteEnrichmentsByFile -> 删除 chunk-enrichments/{knowledgeBaseId}/{fileId}/ 前缀
contentType -> text/plain; charset=utf-8
```

可复用 `MinioChunkObjectStorage` 中的 `ensureBucketExists`、listObjects、removeObject 写法。

---

## 7. Task 6：实现 MyBatis enrichment 仓储

**Files:**

- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/entity/ChunkEnrichmentEntity.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/mapper/ChunkEnrichmentMapper.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisChunkEnrichmentRepository.java`

- [ ] **Step 1: 新增 ChunkEnrichmentEntity**

字段和 `knowledge_file_chunk_enrichment` 表保持一致，`createdAt`、`updatedAt` 使用 `LocalDateTime`。

- [ ] **Step 2: 新增 ChunkEnrichmentMapper**

```java
package com.example.kb.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.kb.infrastructure.persistence.entity.ChunkEnrichmentEntity;

public interface ChunkEnrichmentMapper extends BaseMapper<ChunkEnrichmentEntity> {
}
```

- [ ] **Step 3: 新增 MybatisChunkEnrichmentRepository**

实现：

```text
deleteByFileId(fileId)
save(chunkEnrichment)
toEntity
toDomain
```

日志要求：

```text
删除 enrichment 元数据入参/出参
保存 enrichment 元数据入参/出参
异常捕获处打印堆栈
```

---

## 8. Task 7：实现 AgentScope 生成器

**Files:**

- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/enrichment/ChunkEnrichmentProperties.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/enrichment/ChunkEnrichmentPromptBuilder.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/enrichment/LlmChunkEnrichmentResponse.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/enrichment/AgentScopeChunkEnrichmentGenerator.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/enrichment/MockChunkEnrichmentGenerator.java`
- Modify: `backend/kb-bootstrap/src/main/resources/application.yml`

- [ ] **Step 1: 新增配置**

在 `application.yml` 增加：

```yaml
rag:
  enrichment:
    enabled: true
    provider: dashscope
    model: qwen-plus
    api-key: ${DASHSCOPE_API_KEY:}
    max-questions: 3
    summary-max-chars: 200
    prompt-version: chunk_enrichment_v1
    mock-when-api-key-missing: true
```

- [ ] **Step 2: 新增 ChunkEnrichmentProperties**

使用 `@ConfigurationProperties(prefix = "rag.enrichment")`，字段：

```text
enabled
provider
model
apiKey
maxQuestions
summaryMaxChars
promptVersion
mockWhenApiKeyMissing
```

- [ ] **Step 3: 新增 Prompt Builder**

Prompt 必须要求模型返回 JSON：

```text
你是企业知识库 RAG 索引增强助手。
请只基于给定 chunk 原文生成摘要和用户可能提问。
不要补充外部知识。
必须只返回 JSON，不要输出 Markdown，不要输出解释。

JSON 格式：
{
  "summary": "不超过 {summaryMaxChars} 个中文字符的摘要",
  "questions": [
    {"question": "问题", "type": "specific"},
    {"question": "问题", "type": "summary"},
    {"question": "问题", "type": "scenario"}
  ]
}

文件名：{filename}
标题路径：{titlePath}
chunk 原文：
{chunkContent}
```

- [ ] **Step 4: 新增 LlmChunkEnrichmentResponse**

用于 Jackson 解析：

```java
package com.example.kb.infrastructure.enrichment;

import java.util.List;

public record LlmChunkEnrichmentResponse(
        String summary,
        List<Question> questions
) {
    public record Question(
            String question,
            String type
    ) {
    }
}
```

- [ ] **Step 5: 实现 AgentScopeChunkEnrichmentGenerator**

实现逻辑：

```text
入参日志
构造 prompt
调用 AgentScope 模型
解析 JSON
校验 summary 非空
校验 questions 非空
截断 summary
截断 questions 数量
出参日志
异常日志带堆栈
```

AgentScope 具体调用代码以 Task 1 确认的官方 API 为准。

- [ ] **Step 6: 实现 MockChunkEnrichmentGenerator**

仅当 `mock-when-api-key-missing=true` 且 API Key 为空时使用。

规则：

```text
summary = chunkContent 前 summaryMaxChars 个字符
questions = 3 个模板问题
```

模板问题：

```text
这段内容主要讲什么？
{titlePath} 中有哪些关键信息？
如果我要了解 {filename} 的相关内容，应该关注什么？
```

---

## 9. Task 8：实现 ChunkEnrichmentService

**Files:**

- Create: `backend/kb-application/src/main/java/com/example/kb/application/service/ChunkEnrichmentService.java`

- [ ] **Step 1: 新增服务**

核心方法：

```java
public void rebuildEnrichments(KnowledgeFile file, List<DocumentChunk> chunks)
```

流程：

```text
删除旧 MinIO enrichment 文本
删除旧 MySQL enrichment 元数据
遍历 chunks
读取 chunk 原文
调用 ChunkEnrichmentGenerator
拼 embedding_text
写 MinIO
写 MySQL READY 记录
单个 chunk 失败时写 MySQL FAILED 记录
不向外抛出单个 chunk enrichment 异常
```

- [ ] **Step 2: embedding_text 拼接格式**

```text
标题路径：{titlePath}

摘要：
{summary}

可能问题：
1. {question1}
2. {question2}
3. {question3}

原文：
{chunkContent}
```

- [ ] **Step 3: 失败记录规则**

单个 chunk 失败时保存：

```text
status = FAILED
summary = null
questions_json = null
embedding_text_bucket = 当前 bucket
embedding_text_object_key = 空字符串
error_message = 截断后的异常信息
```

异常信息最大长度：

```text
2048
```

---

## 10. Task 9：挂接到索引 Pipeline 和删除链路

**Files:**

- Modify: `backend/kb-bootstrap/src/main/java/com/example/kb/bootstrap/ApplicationServiceConfiguration.java`
- Modify: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/index/DocumentIndexPipeline.java`
- Modify: `backend/kb-application/src/main/java/com/example/kb/application/service/KnowledgeFileService.java`

- [ ] **Step 1: ApplicationServiceConfiguration 注册服务**

新增 `ChunkEnrichmentService` Bean，注入：

```text
ChunkContentStorage
ChunkEnrichmentGenerator
ChunkEnrichmentObjectStorage
ChunkEnrichmentRepository
```

- [ ] **Step 2: DocumentIndexPipeline 调用 enrichment**

在：

```java
List<DocumentChunk> chunks = documentChunkService.rebuildChunks(file, cleanedDocument);
```

之后调用：

```java
chunkEnrichmentService.rebuildEnrichments(file, chunks);
```

注意：

```text
ChunkEnrichmentService 内部吞掉单个 chunk 的 enrichment 异常。
DocumentIndexPipeline 仍然把文件更新为 READY。
```

- [ ] **Step 3: KnowledgeFileService 删除 enrichment**

删除文件时，在删除 chunk 元数据前后均可，但推荐顺序：

```text
删除向量索引
删除 enrichment MinIO 文本
删除 enrichment MySQL 元数据
删除 chunk MinIO 正文
删除 chunk MySQL 元数据
删除原始文件
删除文件元数据
```

---

## 11. Task 10：本地验证

**Files:**

- No source file changes

- [ ] **Step 1: 用户执行数据库脚本**

在 DBeaver 执行 Task 2 SQL。

- [ ] **Step 2: 用户配置 API Key**

本地 shell 设置：

```bash
export DASHSCOPE_API_KEY=你的Key
```

- [ ] **Step 3: 用户编译后端**

```bash
cd /Users/tangjie/javaai/agent/backend
mvn -q -DskipTests compile
```

期望：

```text
编译成功
```

- [ ] **Step 4: 用户启动后端并上传文件**

上传 `.docx`、`.md` 或 `.txt` 文件。

- [ ] **Step 5: 验证文件 READY**

```sql
SELECT id, original_filename, file_status, parse_error
FROM knowledge_file
ORDER BY id DESC
LIMIT 5;
```

期望：

```text
file_status = READY
```

- [ ] **Step 6: 验证 enrichment 元数据**

```sql
SELECT id, file_id, chunk_id, status, summary, questions_json, error_message, embedding_text_object_key
FROM knowledge_file_chunk_enrichment
ORDER BY id DESC
LIMIT 20;
```

期望：

```text
成功 chunk: status = READY，有 summary，有 questions_json，有 embedding_text_object_key
失败 chunk: status = FAILED，有 error_message，文件仍 READY
```

- [ ] **Step 7: 验证 MinIO**

MinIO 控制台：

```text
http://localhost:9001
```

检查路径：

```text
chunk-enrichments/{knowledgeBaseId}/{fileId}/{chunkId}/embedding-text.txt
```

期望：

```text
能看到 embedding_text.txt
文件内容包含标题路径、摘要、可能问题、原文
```

---

## 12. 计划自查

1. 设计文档中的存储、状态、失败处理、AgentScope 接入均有任务覆盖。
2. 计划没有要求实现 embedding、Milvus、query、rerank 和答案生成。
3. enrichment 失败不影响文件 `READY` 的规则已覆盖。
4. Java 时间字段继续使用 `LocalDateTime`。
5. Java 代码要求不使用 `var`。
6. 未新增 Java 单元测试任务。
