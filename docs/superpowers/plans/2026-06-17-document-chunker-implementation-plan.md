# 文档 Chunk 层 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现上传时选择 chunk 策略，并在文档解析、清洗后生成 chunk，保存 chunk 元数据到 MySQL、完整正文到 MinIO。

**Architecture:** API 接收上传文件和 chunk 参数，文件元数据保存 chunk 配置；索引 pipeline 在 parser 和 cleaner 后调用 chunker；chunker 产生内存 chunk 结果；应用服务负责保存 chunk 元数据和 chunk 正文。第一版不做 embedding、Milvus、query、rerank、LLM chunk。

**Tech Stack:** Java 21、Spring Boot 3.5、MyBatis-Plus、MinIO Java SDK、React、Ant Design。

---

## 0. 范围约束

本计划实现：

1. 上传时配置 chunk 策略。
2. 支持 `FIXED_SIZE`、`SECTION`、`RECURSIVE` 三种策略。
3. 文件级保存 chunk 配置。
4. chunk 元数据保存到 MySQL。
5. chunk 完整正文保存到 MinIO。
6. `DocumentIndexPipeline` 接入 chunk 层。

本计划不实现：

1. 不做 embedding。
2. 不写入 Milvus。
3. 不做 query。
4. 不做 rerank。
5. 不做 HyDE。
6. 不做 Doc2Query。
7. 不做 chunk 摘要。
8. 不做 LLM chunk。
9. 不新增 Java 单元测试。
10. 不由 Codex 执行 npm 命令。

编码约束：

1. Java 不使用 `var`。
2. 时间字段继续使用 `LocalDateTime`。
3. 入参、分支、出参、异常堆栈都打日志。
4. 所有新增 Markdown 文档使用中文。

---

## 1. 文件结构

### 1.1 新增文件

- `backend/kb-domain/src/main/java/com/example/kb/domain/model/ChunkStrategy.java`
  - chunk 策略枚举。
- `backend/kb-domain/src/main/java/com/example/kb/domain/model/ChunkConfig.java`
  - chunk 参数值对象。
- `backend/kb-domain/src/main/java/com/example/kb/domain/model/DocumentChunk.java`
  - chunk 元数据领域模型。
- `backend/kb-application/src/main/java/com/example/kb/application/port/DocumentChunker.java`
  - chunker 端口。
- `backend/kb-application/src/main/java/com/example/kb/application/port/DocumentChunkRepository.java`
  - chunk 元数据仓储端口。
- `backend/kb-application/src/main/java/com/example/kb/application/port/ChunkObjectStorage.java`
  - chunk 正文对象存储端口。
- `backend/kb-application/src/main/java/com/example/kb/application/service/DocumentChunkService.java`
  - chunk 保存应用服务。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/chunk/DefaultDocumentChunker.java`
  - chunker 聚合入口，根据策略分发。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/chunk/FixedSizeChunkStrategy.java`
  - 固定长度策略。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/chunk/SectionChunkStrategy.java`
  - 章节优先策略。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/chunk/RecursiveChunkStrategy.java`
  - 递归策略。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/chunk/ChunkContentBuilder.java`
  - 构造带标题路径的 chunk content。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/chunk/ChunkHash.java`
  - SHA-256 hash 工具。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/entity/DocumentChunkEntity.java`
  - chunk MyBatis entity。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/mapper/DocumentChunkMapper.java`
  - chunk mapper。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisDocumentChunkRepository.java`
  - chunk repository 实现。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/storage/MinioChunkObjectStorage.java`
  - chunk 正文 MinIO 存储实现。

### 1.2 修改文件

- `backend/kb-domain/src/main/java/com/example/kb/domain/model/KnowledgeFile.java`
  - 增加 chunk 配置字段。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/entity/KnowledgeFileEntity.java`
  - 增加 chunk 配置字段。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisKnowledgeFileRepository.java`
  - 映射 chunk 配置字段。
- `backend/kb-application/src/main/java/com/example/kb/application/service/KnowledgeFileService.java`
  - 上传时接收并校验 chunk 配置。
- `backend/kb-api/src/main/java/com/example/kb/api/controller/KnowledgeFileController.java`
  - 上传接口接收 chunk 参数。
- `backend/kb-api/src/main/java/com/example/kb/api/dto/KnowledgeFileDtos.java`
  - 文件响应返回 chunk 配置。
- `backend/kb-bootstrap/src/main/java/com/example/kb/bootstrap/ApplicationServiceConfiguration.java`
  - 注册 `DocumentChunkService`。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/index/DocumentIndexPipeline.java`
  - cleaner 后接入 chunk 保存。
- `backend/kb-infrastructure/src/main/resources/db/schema.sql`
  - 增加 knowledge_file 字段和 knowledge_file_chunk 表。
- `frontend/src/types/domain.ts`
  - 增加 chunk 类型字段。
- `frontend/src/api/knowledgeFileApi.ts`
  - 上传接口传 chunk 参数。
- `frontend/src/pages/KnowledgeBaseLayout.tsx`
  - 上传前选择 chunk 策略、chunkSize、chunkOverlap。
- `frontend/src/components/FileDetailDrawer.tsx`
  - 展示文件 chunk 配置。

---

## Task 1: 新增 chunk 领域模型

**Files:**
- Create: `backend/kb-domain/src/main/java/com/example/kb/domain/model/ChunkStrategy.java`
- Create: `backend/kb-domain/src/main/java/com/example/kb/domain/model/ChunkConfig.java`
- Create: `backend/kb-domain/src/main/java/com/example/kb/domain/model/DocumentChunk.java`
- Modify: `backend/kb-domain/src/main/java/com/example/kb/domain/model/KnowledgeFile.java`

- [ ] **Step 1: 创建 `ChunkStrategy`**

要求：

```java
package com.example.kb.domain.model;

public enum ChunkStrategy {
    FIXED_SIZE,
    SECTION,
    RECURSIVE
}
```

- [ ] **Step 2: 创建 `ChunkConfig`**

字段：

```java
Long knowledgeBaseId
Long fileId
ChunkStrategy chunkStrategy
int chunkSize
int chunkOverlap
```

规则：

1. 默认策略：`RECURSIVE`。
2. 默认 `chunkSize=1000`。
3. 默认 `chunkOverlap=150`。
4. `chunkSize` 范围：`200-4000`。
5. `chunkOverlap` 范围：`0-1000`。
6. `chunkOverlap < chunkSize`。

建议提供静态方法：

```java
ChunkConfig normalize(Long knowledgeBaseId, Long fileId, ChunkStrategy strategy, Integer size, Integer overlap)
```

- [ ] **Step 3: 创建 `DocumentChunk`**

字段：

```java
Long id
Long knowledgeBaseId
Long fileId
String sectionId
String parentSectionId
int chunkIndex
ChunkStrategy chunkStrategy
int chunkSize
int chunkOverlap
String titlePath
String contentPreview
String contentHash
int contentSize
Integer startOffset
Integer endOffset
String storageBucket
String storageObjectKey
LocalDateTime createdAt
LocalDateTime updatedAt
```

- [ ] **Step 4: 扩展 `KnowledgeFile`**

增加字段：

```java
ChunkStrategy chunkStrategy
int chunkSize
int chunkOverlap
```

所有构造调用点需要同步补充字段。

---

## Task 2: 修改数据库结构

**Files:**
- Modify: `backend/kb-infrastructure/src/main/resources/db/schema.sql`

- [ ] **Step 1: 给 `knowledge_file` 增加字段**

在建表 SQL 中增加：

```sql
chunk_strategy VARCHAR(64) NOT NULL DEFAULT 'RECURSIVE',
chunk_size INT NOT NULL DEFAULT 1000,
chunk_overlap INT NOT NULL DEFAULT 150,
```

- [ ] **Step 2: 新增 `knowledge_file_chunk` 表**

SQL：

```sql
CREATE TABLE IF NOT EXISTS knowledge_file_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    section_id VARCHAR(128) NULL,
    parent_section_id VARCHAR(128) NULL,
    chunk_index INT NOT NULL,
    chunk_strategy VARCHAR(64) NOT NULL,
    chunk_size INT NOT NULL,
    chunk_overlap INT NOT NULL,
    title_path VARCHAR(1024) NULL,
    content_preview VARCHAR(512) NULL,
    content_hash VARCHAR(128) NOT NULL,
    content_size INT NOT NULL,
    start_offset INT NULL,
    end_offset INT NULL,
    storage_bucket VARCHAR(255) NOT NULL,
    storage_object_key VARCHAR(1024) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_chunk_file_id (file_id),
    KEY idx_chunk_kb_file (knowledge_base_id, file_id),
    KEY idx_chunk_strategy (chunk_strategy)
);
```

- [ ] **Step 3: 给用户提供手动升级 SQL**

由于本地 MySQL 已经初始化过，需要用户执行：

```sql
ALTER TABLE knowledge_file
    ADD COLUMN chunk_strategy VARCHAR(64) NOT NULL DEFAULT 'RECURSIVE',
    ADD COLUMN chunk_size INT NOT NULL DEFAULT 1000,
    ADD COLUMN chunk_overlap INT NOT NULL DEFAULT 150;

CREATE TABLE IF NOT EXISTS knowledge_file_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    section_id VARCHAR(128) NULL,
    parent_section_id VARCHAR(128) NULL,
    chunk_index INT NOT NULL,
    chunk_strategy VARCHAR(64) NOT NULL,
    chunk_size INT NOT NULL,
    chunk_overlap INT NOT NULL,
    title_path VARCHAR(1024) NULL,
    content_preview VARCHAR(512) NULL,
    content_hash VARCHAR(128) NOT NULL,
    content_size INT NOT NULL,
    start_offset INT NULL,
    end_offset INT NULL,
    storage_bucket VARCHAR(255) NOT NULL,
    storage_object_key VARCHAR(1024) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_chunk_file_id (file_id),
    KEY idx_chunk_kb_file (knowledge_base_id, file_id),
    KEY idx_chunk_strategy (chunk_strategy)
);
```

---

## Task 3: 映射文件级 chunk 配置

**Files:**
- Modify: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/entity/KnowledgeFileEntity.java`
- Modify: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisKnowledgeFileRepository.java`
- Modify: `backend/kb-api/src/main/java/com/example/kb/api/dto/KnowledgeFileDtos.java`

- [ ] **Step 1: Entity 增加字段**

`KnowledgeFileEntity` 增加：

```java
private String chunkStrategy;
private Integer chunkSize;
private Integer chunkOverlap;
```

并增加 getter/setter。

- [ ] **Step 2: Repository 映射**

`toDomain` 中把字符串转为 `ChunkStrategy.valueOf(entity.getChunkStrategy())`。

`toEntity` 中写入：

```java
entity.setChunkStrategy(file.chunkStrategy().name());
entity.setChunkSize(file.chunkSize());
entity.setChunkOverlap(file.chunkOverlap());
```

- [ ] **Step 3: DTO 响应增加字段**

`KnowledgeFileDtos.Response` 增加：

```java
ChunkStrategy chunkStrategy
int chunkSize
int chunkOverlap
```

前端详情可展示这些配置。

---

## Task 4: 上传接口接收 chunk 配置

**Files:**
- Modify: `backend/kb-api/src/main/java/com/example/kb/api/controller/KnowledgeFileController.java`
- Modify: `backend/kb-application/src/main/java/com/example/kb/application/service/KnowledgeFileService.java`

- [ ] **Step 1: Controller 增加 request 参数**

上传接口增加：

```java
@RequestParam(name = "chunkStrategy", required = false) ChunkStrategy chunkStrategy,
@RequestParam(name = "chunkSize", required = false) Integer chunkSize,
@RequestParam(name = "chunkOverlap", required = false) Integer chunkOverlap
```

- [ ] **Step 2: Service upload 签名增加参数**

`KnowledgeFileService.upload` 增加：

```java
ChunkStrategy chunkStrategy,
Integer chunkSize,
Integer chunkOverlap
```

- [ ] **Step 3: 归一化配置**

调用：

```java
ChunkConfig chunkConfig = ChunkConfig.normalize(knowledgeBaseId, null, chunkStrategy, chunkSize, chunkOverlap);
```

保存 `KnowledgeFile` 前使用归一化后的策略和参数。

- [ ] **Step 4: 日志**

上传入参日志增加：

```text
chunkStrategy, chunkSize, chunkOverlap
```

分支日志增加：

```text
chunk 配置归一化成功
```

---

## Task 5: 新增 chunk 元数据持久化

**Files:**
- Create: `backend/kb-application/src/main/java/com/example/kb/application/port/DocumentChunkRepository.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/entity/DocumentChunkEntity.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/mapper/DocumentChunkMapper.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisDocumentChunkRepository.java`

- [ ] **Step 1: Repository 端口**

方法：

```java
void deleteByFileId(Long fileId);

DocumentChunk save(DocumentChunk chunk);

List<DocumentChunk> findByFileId(Long fileId);
```

- [ ] **Step 2: Entity/Mapper**

按 `knowledge_file_chunk` 表字段创建 entity 和 mapper。

- [ ] **Step 3: Repository 实现**

要求：

1. `save` 支持 insert。
2. `deleteByFileId` 用于重建前清理旧 chunk。
3. 所有方法记录入参、分支、出参日志。

---

## Task 6: 新增 chunk 正文 MinIO 存储

**Files:**
- Create: `backend/kb-application/src/main/java/com/example/kb/application/port/ChunkObjectStorage.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/storage/MinioChunkObjectStorage.java`

- [ ] **Step 1: 定义端口**

方法：

```java
StoredChunkObject putChunk(PutChunkCommand command);

void deleteChunksByFile(Long knowledgeBaseId, Long fileId);
```

`PutChunkCommand` 字段：

```java
Long knowledgeBaseId
Long fileId
int chunkIndex
String contentHash
String content
```

`StoredChunkObject` 字段：

```java
String bucket
String objectKey
```

- [ ] **Step 2: MinIO key 规则**

object key：

```text
chunks/{knowledgeBaseId}/{fileId}/{chunkIndex}-{hashPrefix}.txt
```

`chunkIndex` 使用 6 位补零。

- [ ] **Step 3: 删除规则**

`deleteChunksByFile` 按前缀：

```text
chunks/{knowledgeBaseId}/{fileId}/
```

删除所有 chunk 正文对象。

---

## Task 7: 实现 chunker 端口和策略

**Files:**
- Create: `backend/kb-application/src/main/java/com/example/kb/application/port/DocumentChunker.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/chunk/DefaultDocumentChunker.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/chunk/FixedSizeChunkStrategy.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/chunk/SectionChunkStrategy.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/chunk/RecursiveChunkStrategy.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/chunk/ChunkContentBuilder.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/chunk/ChunkHash.java`

- [ ] **Step 1: `DocumentChunker` 端口**

方法：

```java
List<ChunkDraft> chunk(ParsedDocument document, ChunkConfig chunkConfig);
```

`ChunkDraft` 可作为接口内 record，字段：

```java
String sectionId
String parentSectionId
int chunkIndex
String titlePath
String content
String contentHash
int contentSize
Integer startOffset
Integer endOffset
```

- [ ] **Step 2: `DefaultDocumentChunker`**

根据 `chunkConfig.chunkStrategy()` 分发到三个策略。

- [ ] **Step 3: `FixedSizeChunkStrategy`**

规则：

1. 按 section 顺序拼文本流。
2. 每段正文前拼 `title_path`。
3. 按 `chunkSize/chunkOverlap` 切。
4. `sectionId` 使用 chunk 起始位置所在 section。

- [ ] **Step 4: `SectionChunkStrategy`**

规则：

1. 每个 section 独立处理。
2. section 小于等于 `chunkSize`，生成一个 chunk。
3. section 超长，用固定长度在 section 内切。
4. overlap 不跨 section。

- [ ] **Step 5: `RecursiveChunkStrategy`**

规则：

1. 每个 section 独立处理。
2. 优先按段落组合。
3. 段落超长按中文/英文句末符号切句。
4. 句子超长按字符兜底。
5. overlap 不跨 section。

句子边界建议：

```text
。！？；.!?;
```

- [ ] **Step 6: `ChunkContentBuilder`**

统一 chunk content 格式：

```text
标题路径：xxx

正文
```

如果没有 `title_path`，使用 section title，再没有则使用文件名。

---

## Task 8: 新增 `DocumentChunkService`

**Files:**
- Create: `backend/kb-application/src/main/java/com/example/kb/application/service/DocumentChunkService.java`
- Modify: `backend/kb-bootstrap/src/main/java/com/example/kb/bootstrap/ApplicationServiceConfiguration.java`

- [ ] **Step 1: 服务职责**

`DocumentChunkService` 负责：

1. 根据文件配置生成 `ChunkConfig`。
2. 调用 `DocumentChunker`。
3. 删除旧 chunk metadata。
4. 删除旧 chunk MinIO 正文。
5. 保存新 chunk 正文到 MinIO。
6. 保存新 chunk metadata 到 MySQL。

- [ ] **Step 2: 方法设计**

方法：

```java
List<DocumentChunk> rebuildChunks(KnowledgeFile file, ParsedDocument document);
```

- [ ] **Step 3: 注册 Bean**

如果该服务不是 `@Service`，则在 `ApplicationServiceConfiguration` 注册 Bean。

---

## Task 9: 接入 `DocumentIndexPipeline`

**Files:**
- Modify: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/index/DocumentIndexPipeline.java`

- [ ] **Step 1: 注入 `DocumentChunkService`**

在 pipeline 构造函数中增加 `DocumentChunkService`。

- [ ] **Step 2: cleaner 后调用 chunk**

流程改为：

```text
parse
  -> clean
  -> rebuildChunks
  -> READY
```

- [ ] **Step 3: 成功消息**

成功消息改为：

```text
文档解析、清洗和 chunk 成功，sectionCount=..., chunkCount=...
```

- [ ] **Step 4: 失败处理**

chunk 失败第一版继续：

```text
file_status = PARSE_FAILED
task_status = FAILED
parse_error = 简短错误原因
```

---

## Task 10: 前端上传配置

**Files:**
- Modify: `frontend/src/types/domain.ts`
- Modify: `frontend/src/api/knowledgeFileApi.ts`
- Modify: `frontend/src/pages/KnowledgeBaseLayout.tsx`
- Modify: `frontend/src/components/FileDetailDrawer.tsx`

- [ ] **Step 1: 类型增加**

`domain.ts` 增加：

```ts
export type ChunkStrategy = 'FIXED_SIZE' | 'SECTION' | 'RECURSIVE';
```

`KnowledgeFile` 增加：

```ts
chunkStrategy: ChunkStrategy;
chunkSize: number;
chunkOverlap: number;
```

- [ ] **Step 2: API 上传参数**

`uploadFile` 签名改为：

```ts
uploadFile(knowledgeBaseId: number, file: File, options: UploadChunkOptions)
```

FormData 增加：

```ts
formData.append('chunkStrategy', options.chunkStrategy);
formData.append('chunkSize', String(options.chunkSize));
formData.append('chunkOverlap', String(options.chunkOverlap));
```

- [ ] **Step 3: 上传 UI**

在上传按钮附近增加：

1. 策略 Select，默认 `RECURSIVE`。
2. chunkSize InputNumber，默认 `1000`。
3. chunkOverlap InputNumber，默认 `150`。

UI 文案：

```text
切分策略
块大小
重叠长度
```

- [ ] **Step 4: 前端校验**

规则：

1. `chunkSize` 在 `200-4000`。
2. `chunkOverlap` 在 `0-1000`。
3. `chunkOverlap < chunkSize`。

- [ ] **Step 5: 详情展示**

文件详情展示：

```text
切分策略
块大小
重叠长度
```

---

## Task 11: 验证

**Files:**
- No code files.

- [ ] **Step 1: 静态扫描**

运行：

```bash
rg -n "\bvar\b|Instant|OffsetDateTime|ZonedDateTime|java\.util\.Date|Timestamp" backend
```

预期：无输出。

- [ ] **Step 2: Maven 编译**

由用户执行：

```bash
cd /Users/tangjie/javaai/agent/backend
mvn -q -DskipTests compile
```

预期：编译成功。

- [ ] **Step 3: 前端构建**

由用户执行：

```bash
cd /Users/tangjie/javaai/agent/frontend
npm run build
```

预期：构建成功。

- [ ] **Step 4: 数据库升级**

由用户在 DBeaver 或 mysql cli 执行 Task 2 的升级 SQL。

- [ ] **Step 5: 上传验证**

分别上传 TXT、Markdown、DOCX，并选择不同策略：

1. TXT + `FIXED_SIZE`
2. Markdown + `SECTION`
3. DOCX + `RECURSIVE`

预期：

1. 文件最终 `READY`。
2. 任务最终 `SUCCESS`。
3. `knowledge_file_chunk` 有对应记录。
4. MinIO 有 `chunks/{knowledgeBaseId}/{fileId}/...txt` 对象。
5. 日志中有 chunkCount。

---

## 计划自查

1. 覆盖了上传时选择策略。
2. 覆盖了三种 chunk 策略。
3. 覆盖了 MySQL metadata 和 MinIO 正文存储。
4. 没有实现 embedding、Milvus、query、rerank。
5. 没有安排 Java 单元测试。
6. npm 命令明确由用户执行。
7. Java 约束中明确不使用 `var` 和非 `LocalDateTime` 时间类型。
