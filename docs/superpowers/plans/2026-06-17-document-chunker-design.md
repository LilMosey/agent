# 文档 Chunk 层设计

## 1. 背景

当前离线索引链路已经完成：

```text
上传文件
  -> 创建索引任务
  -> Parser
  -> ParsedDocument
  -> DocumentCleaner
  -> ParsedDocument
```

下一步需要增加 chunk 层，把清洗后的 `ParsedDocument` 切分为适合 embedding、召回、rerank 和引用展示的文本块。

本阶段只设计 chunk 层，不实现 embedding、Milvus 写入、query、rerank、答案生成和 LLM chunk。

## 2. 设计目标

Chunk 层目标如下：

1. 支持上传文件时选择 chunk 策略。
2. 第一版支持三个确定性策略：固定长度、章节优先、递归切分。
3. chunk 结果可溯源到知识库、文件、section、标题路径。
4. 完整 chunk 正文不直接存 MySQL，而是存 MinIO。
5. MySQL 保存 chunk 元数据和预览文本。
6. 为后续 embedding 和 Milvus 写入留出接口。

## 3. 本阶段不做的内容

本阶段不做以下内容：

1. 不做 LLM chunk。
2. 不做 semantic chunk。
3. 不做 embedding。
4. 不写入 Milvus。
5. 不做 HyDE。
6. 不做 Doc2Query。
7. 不做 chunk 摘要。
8. 不做 query 和 rerank。

LLM chunk 后续可以预留策略枚举，但第一版不开放给用户。

## 4. 上传时配置

Chunk 策略在上传文件时配置，并随文件保存。

原因：

1. 用户可能希望不同文件使用不同切分策略。
2. 文件级配置比知识库级配置更灵活。
3. 历史文件的 chunk 参数需要可追溯。

上传参数建议如下：

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| chunkStrategy | enum | RECURSIVE | chunk 策略 |
| chunkSize | int | 1000 | 目标 chunk 字符数 |
| chunkOverlap | int | 150 | chunk 重叠字符数 |

参数校验建议：

1. `chunkSize` 范围：`200-4000`。
2. `chunkOverlap` 范围：`0-1000`。
3. `chunkOverlap` 必须小于 `chunkSize`。
4. 未传参数时使用默认值。

## 5. 三种 chunk 策略

### 5.1 固定长度策略：FIXED_SIZE

规则：

1. 忽略 section 边界，把所有 section 按顺序拼成一个文本流。
2. 每个 chunk 目标长度为 `chunkSize`。
3. 相邻 chunk 使用 `chunkOverlap`。
4. chunk content 前拼接对应位置的 `title_path`。

示例：

```text
chunkSize = 1000
chunkOverlap = 150

chunk1: 0-1000
chunk2: 850-1850
chunk3: 1700-2700
```

适用场景：

1. TXT 文件。
2. 结构较差的文档。
3. 快速 baseline。

优点：

1. 实现简单。
2. 稳定可预测。
3. 参数容易理解。

缺点：

1. 可能切断段落或句子。
2. 章节语义保留较弱。

### 5.2 章节优先策略：SECTION

规则：

1. 优先按 `DocumentSection` 生成 chunk。
2. section 内容长度小于等于 `chunkSize` 时，一个 section 生成一个 chunk。
3. section 内容超过 `chunkSize` 时，在该 section 内使用固定长度兜底切分。
4. overlap 只在同一个 section 内生效，不跨 section。
5. chunk content 前拼接当前 section 的 `title_path`。

适用场景：

1. Markdown。
2. DOCX。
3. 标题结构质量较好的文档。

优点：

1. 保留章节语义。
2. 溯源清晰。
3. 不容易把不同主题硬切到一起。

缺点：

1. 依赖解析出的 section 质量。
2. 如果某个 section 很大，仍需要兜底切分。

### 5.3 递归切分策略：RECURSIVE

规则：

按以下优先级递归切分：

```text
Section
  -> 段落
  -> 句子
  -> 字符长度兜底
```

具体规则：

1. 先遍历 section。
2. section 小于等于 `chunkSize` 时，直接生成 chunk。
3. section 超长时，按段落拆分。
4. 段落组合不超过 `chunkSize` 时，尽量合并到同一个 chunk。
5. 单个段落仍超长时，按句子拆分。
6. 单个句子仍超长时，按字符长度切分。
7. overlap 只在同一个 section 内生效，不跨 section。
8. chunk content 前拼接当前 section 的 `title_path`。

适用场景：

1. 默认推荐策略。
2. 多数 Markdown 和 DOCX 文档。
3. 需要在语义自然和长度均衡之间取得平衡的场景。

优点：

1. 比固定长度更自然。
2. 比纯章节优先更均衡。
3. 不依赖 LLM。
4. 成本低、稳定。

缺点：

1. 中文句子边界需要规则兜底。
2. 实现比固定长度复杂。

## 6. chunk content 规则

第一版 chunk content 拼接标题路径。

格式：

```text
标题路径：产品手册 > 部署说明 > Docker

正文内容...
```

原因：

1. embedding 时能获得章节上下文。
2. 短正文 chunk 更容易被召回。
3. 后续展示引用来源更直观。

注意：

1. `title_path` 仍然保留在 metadata。
2. 每个 chunk 只拼一次标题路径。
3. 如果没有 `title_path`，使用 section title 或文件名。

## 7. overlap 规则

第一版 overlap 规则：

1. overlap 只在同一个 section 内生效。
2. 不跨 section overlap。
3. 固定长度策略如果跨 section 拼接文本流，需要记录 chunk 主 section 信息。
4. SECTION 和 RECURSIVE 策略以 section 为边界，不跨 section。

不跨 section 的原因：

1. 避免不同主题被硬粘在一起。
2. 保持引用来源清晰。
3. 降低 chunk metadata 复杂度。

## 8. 数据模型设计

### 8.1 ChunkStrategy

新增枚举：

```text
FIXED_SIZE
SECTION
RECURSIVE
```

可以预留但不开放：

```text
LLM_RESERVED
```

### 8.2 knowledge_file 增加字段

建议新增字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| chunk_strategy | VARCHAR(64) | 上传时选择的 chunk 策略 |
| chunk_size | INT | 上传时选择的 chunk 大小 |
| chunk_overlap | INT | 上传时选择的重叠长度 |

这些字段保存文件级 chunk 配置。

### 8.3 knowledge_file_chunk 表

建议新增表：

```sql
CREATE TABLE knowledge_file_chunk (
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

说明：

1. MySQL 只存 metadata 和 preview。
2. 完整 chunk 正文放 MinIO。
3. 后续 embedding 字段可以单独建表，或者再扩展本表。

## 9. MinIO 存储设计

完整 chunk 正文存 MinIO。

建议 object key：

```text
chunks/{knowledgeBaseId}/{fileId}/{chunkIndex}-{contentHashPrefix}.txt
```

示例：

```text
chunks/1/8/000001-a1b2c3d4.txt
```

优点：

1. 不依赖数据库自增 ID 生成 object key。
2. 同一个文件的 chunk 容易定位。
3. 删除文件时可按前缀清理。

## 10. 删除与重建

文件删除时需要删除：

1. 原始文件对象。
2. chunk 正文对象。
3. chunk 元数据。
4. 后续 Milvus 向量。

重新上传同名文件时，当前规则仍然是必须先删除旧文件，再上传新文件。

## 11. 与 embedding 的关系

Chunk 层不调用 embedding。

后续链路：

```text
Chunker
  -> 保存 chunk metadata 到 MySQL
  -> 保存 chunk 正文到 MinIO
  -> EmbeddingPipeline
  -> Milvus
```

Embedding 输入建议第一版使用 chunk content：

```text
标题路径：xxx

正文内容
```

后续可以扩展：

1. chunk summary embedding。
2. Doc2Query embedding。
3. 多向量索引。

## 12. 状态流转

当前文件状态可以继续复用：

```text
PENDING_PARSE -> PARSING -> READY
PENDING_PARSE -> PARSING -> PARSE_FAILED
```

如果后续希望更细，可以扩展：

```text
PENDING_PARSE
PARSING
CHUNKING
CHUNK_FAILED
READY
```

第一版为了简化，chunk 失败可以先复用 `PARSE_FAILED`，错误信息写入 `parse_error`。

## 13. 推荐结论

第一版推荐：

1. 上传时选择 chunk 策略。
2. 文件级保存 `chunk_strategy`、`chunk_size`、`chunk_overlap`。
3. 支持 `FIXED_SIZE`、`SECTION`、`RECURSIVE` 三种策略。
4. 默认策略为 `RECURSIVE`。
5. 默认参数：`chunkSize=1000`，`chunkOverlap=150`。
6. chunk content 拼接 `title_path`。
7. MySQL 保存 chunk metadata 和 preview。
8. MinIO 保存完整 chunk 正文。
9. 不支持 LLM chunk，但预留后续扩展。
