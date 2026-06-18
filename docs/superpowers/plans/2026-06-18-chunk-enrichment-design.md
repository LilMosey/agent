# Chunk Enrichment 层设计

## 1. 背景

当前离线索引链路已经完成：

```text
上传文件
  -> 创建索引任务
  -> Parser
  -> ParsedDocument
  -> DocumentCleaner
  -> ParsedDocument
  -> Chunker
  -> chunk 正文写 MinIO
  -> chunk 元数据写 MySQL
```

下一步需要在 chunk 后、embedding 前增加 `Chunk Enrichment` 层。它负责基于每个 chunk 的原始正文生成摘要、模拟问题，并拼接出后续用于 embedding 的增强文本。

本阶段只设计 `Chunk Enrichment` 层。第一版计划通过 AgentScope Java 接入真实 LLM 生成摘要和模拟问题，但不实现 embedding、Milvus 写入、query、rerank 和答案生成。

## 2. 设计目标

`Chunk Enrichment` 层目标如下：

1. 基于 chunk 原文生成摘要 `summary`。
2. 基于 chunk 原文生成模拟问题 `questions`。
3. 拼接一份只用于 embedding 的增强文本 `embedding_text`。
4. 保证最终查询召回后，展示和喂给大模型的仍然是原始 chunk 正文。
5. MySQL 保存结构化增强元数据，方便调试和追踪。
6. MinIO 保存较长的增强文本，避免 MySQL 存大段正文。
7. 为后续真实 LLM、Doc2Query、多向量索引和 HyDE 留扩展口子。

## 3. 本阶段不做的内容

本阶段不做以下内容：

1. 不调用 embedding 模型。
2. 不写入 Milvus。
3. 不做 query。
4. 不做 rerank。
5. 不做答案生成。
6. 不做在线 HyDE。
7. 不做多向量索引。
8. 不做摘要质量评估。
9. 不做复杂 Agent 编排。

第一版接入真实 LLM，但只把 AgentScope 当作统一模型调用框架使用，不在本阶段开发完整 Agent 应用。

## 4. Enrichment 在链路中的位置

推荐链路如下：

```text
Parser
  -> Cleaner
  -> Chunker
  -> ChunkEnrichment
  -> Embedding
  -> Milvus
```

`ChunkEnrichment` 的输入是已经存好的 chunk：

```text
knowledge_file_chunk
MinIO chunk 正文 txt
```

`ChunkEnrichment` 的输出是：

```text
summary
questions_json
embedding_text
```

## 5. 核心概念

### 5.1 原始 chunk 正文

原始 chunk 正文是当前已经写入 MinIO 的文本。

用途：

1. 后续查询召回后的引用正文。
2. 最终给大模型生成答案的上下文。
3. 前端详情和调试时查看原文。

注意：原始 chunk 正文不应该被摘要和模拟问题污染。

### 5.2 summary

`summary` 是大模型基于 chunk 原文生成的短摘要。

用途：

1. 帮助人工快速理解 chunk 内容。
2. 后续可以参与 embedding。
3. 后续可以用于 rerank 前的轻量预览。

建议长度：

```text
80-200 中文字
```

摘要应该忠实于 chunk 原文，不补充外部知识。

### 5.3 questions

`questions` 是大模型基于 chunk 原文模拟出来的用户可能提问。

这类能力更接近：

```text
Doc2Query
反向 HyDE
Question Index
```

它不是严格意义上的在线 HyDE。在线 HyDE 是用户查询时先生成假设答案，再用假设答案检索。这里是在离线索引阶段提前根据 chunk 生成可能问题。

建议每个 chunk 生成：

```text
3-5 个问题
```

问题类型可以先分为：

| 类型 | 说明 |
| --- | --- |
| specific | 针对 chunk 中具体事实的问题 |
| summary | 针对 chunk 主旨的问题 |
| scenario | 模拟业务场景式提问 |

### 5.4 embedding_text

`embedding_text` 是后续真正送去 embedding 的文本。

第一版推荐格式：

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

注意：`embedding_text` 只用于生成向量，不直接展示给用户，也不默认给大模型作为最终回答上下文。

## 6. 推荐存储方式

### 6.1 MySQL 存结构化元数据

建议新增表：

```text
knowledge_file_chunk_enrichment
```

字段建议：

```sql
CREATE TABLE knowledge_file_chunk_enrichment (
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

说明：

1. `summary` 存短摘要，方便 DBeaver 和前端调试。
2. `questions_json` 存模拟问题数组。
3. `embedding_text_object_key` 指向 MinIO 中的增强文本。
4. `llm_provider`、`llm_model`、`prompt_version` 为后续真实 LLM 留追踪字段。
5. `enrichment_strategy` 用于区分不同增强策略。

### 6.2 MinIO 存增强文本

建议 object key：

```text
chunk-enrichments/{knowledgeBaseId}/{fileId}/{chunkId}/embedding-text.txt
```

示例：

```text
chunk-enrichments/1/8/39/embedding-text.txt
```

这样删除文件时，可以按前缀删除：

```text
chunk-enrichments/{knowledgeBaseId}/{fileId}/
```

### 6.3 Milvus 后续只存向量和过滤元数据

Milvus 后续建议存：

```text
vector
knowledge_base_id
file_id
chunk_id
embedding_type
title_path
```

不建议在 Milvus 存大段正文。查询命中后，通过 `chunk_id` 回查 MySQL，再从 MinIO 取原始 chunk 正文。

## 7. questions_json 格式

建议格式：

```json
[
  {
    "question": "文件上传后系统会做哪些处理？",
    "type": "specific"
  },
  {
    "question": "chunk 正文存在哪里？",
    "type": "specific"
  },
  {
    "question": "如何理解知识库文件的离线索引流程？",
    "type": "summary"
  }
]
```

第一版也可以只存字符串数组：

```json
[
  "文件上传后系统会做哪些处理？",
  "chunk 正文存在哪里？",
  "如何理解知识库文件的离线索引流程？"
]
```

推荐使用对象数组。原因是后续可以按问题类型做调试和评估。

## 8. EnrichmentStrategy

建议预留增强策略枚举：

```text
HYBRID_TEXT
```

第一版只实现 `HYBRID_TEXT`。

含义：

```text
summary + questions + 原文 拼成一份 embedding_text
```

后续可以扩展：

```text
SUMMARY_ONLY
QUESTION_ONLY
ORIGINAL_ONLY
MULTI_VECTOR_RESERVED
```

## 9. 状态设计

建议 enrichment 状态：

```text
PENDING
RUNNING
READY
FAILED
```

第一版如果和 chunk 同步执行，可以暂时不单独建任务表，只在 `knowledge_file_chunk_enrichment.status` 上记录结果。

后续如果真实接 LLM，建议改成单独任务：

```text
knowledge_file_chunk_enrichment_task
```

原因：

1. LLM 调用可能慢。
2. LLM 调用可能失败。
3. 需要重试。
4. 需要限流。
5. 可能需要异步批处理。

## 10. AgentScope LLM 接入设计

第一版计划通过 AgentScope Java 在 Spring Boot 后端中接入真实 LLM。

当前使用方式定位：

```text
AgentScope Java
  -> 统一模型调用入口
  -> 生成 chunk summary
  -> 生成 chunk questions
```

本阶段不使用 AgentScope 做复杂 Agent 编排，也不引入工具调用、多智能体协作、长期记忆和计划模式。

推荐在应用层定义端口：

```text
ChunkEnrichmentGenerator
```

职责：

1. 输入 chunk 原文、标题路径、文件名。
2. 调用 AgentScope 封装的 LLM。
3. 返回结构化结果：`summary` 和 `questions`。
4. 不负责写 MySQL。
5. 不负责写 MinIO。
6. 不负责 embedding。

AgentScope 适配器放在 infrastructure 层：

```text
AgentScopeChunkEnrichmentGenerator
```

职责：

1. 读取模型配置。
2. 构造 prompt。
3. 调用 AgentScope 模型能力。
4. 解析模型返回的 JSON。
5. 对异常、空返回、JSON 解析失败进行日志记录。

配置建议：

```yaml
rag:
  enrichment:
    enabled: true
    provider: dashscope
    model: qwen-plus
    maxQuestions: 3
    summaryMaxChars: 200
    promptVersion: chunk_enrichment_v1
```

API Key 不写入代码仓库，建议通过环境变量或本地配置提供。

## 11. LLM 输出格式

第一版要求 LLM 返回严格 JSON。

推荐格式：

```json
{
  "summary": "本段介绍知识库文件上传后的解析、清洗和分块流程。",
  "questions": [
    {
      "question": "文件上传后系统会做哪些处理？",
      "type": "specific"
    },
    {
      "question": "chunk 正文存在哪里？",
      "type": "specific"
    },
    {
      "question": "如何理解知识库文件的离线索引流程？",
      "type": "summary"
    }
  ]
}
```

解析规则：

1. `summary` 为空时，本 chunk enrichment 失败。
2. `questions` 为空时，可以保存摘要，但状态标记为 `READY_WITH_WARNINGS`，或者第一版直接按失败处理。
3. 问题数量超过配置上限时截断。
4. `summary` 超长时截断并记录日志。
5. JSON 解析失败时进入失败状态，保存错误信息。

为保持第一版简单，建议先不引入 `READY_WITH_WARNINGS`，失败就写 `FAILED`。

## 12. Prompt 设计

第一版 prompt 目标是稳定生成摘要和问题，不做改写原文。

约束：

1. 只能基于给定 chunk 原文生成。
2. 不允许补充外部知识。
3. 摘要需要忠实、简洁。
4. 问题需要像真实企业用户会问的问题。
5. 必须返回 JSON，不输出额外解释。

Prompt 输入建议：

```text
文件名：{filename}
标题路径：{titlePath}
chunk 正文：
{chunkContent}
```

Prompt 输出字段：

```text
summary
questions[].question
questions[].type
```

## 13. 失败处理与重试

由于第一版接真实 LLM，必须把失败当作正常情况处理。

推荐规则：

1. LLM 调用失败时，当前 chunk enrichment 状态写 `FAILED`。
2. 错误信息写入 `error_message`。
3. 不影响原始 chunk 正文和 chunk 元数据。
4. 文件状态仍然可以进入 `READY`。
5. 后续 embedding 阶段如果发现 enrichment 失败，可以回退使用原始 chunk 正文生成向量。

第一版推荐：

```text
只要 parser、cleaner、chunk 成功，文件就可以进入 READY。
如果部分 enrichment 失败，失败原因记录在 knowledge_file_chunk_enrichment.error_message。
后续 embedding 阶段对失败 chunk 回退使用原始 chunk 正文。
```

原因：

1. Enrichment 是召回增强，不是原文索引的唯一来源。
2. LLM 失败不应该让整个文件不可用。
3. demo 阶段更容易调试。
4. 即使没有摘要和模拟问题，原始 chunk 仍然可以被索引和查询。

如果后续要求所有 chunk 都必须有增强结果，再把文件状态扩展为：

```text
ENRICHING
ENRICH_FAILED
READY
```

## 14. 第一版推荐实现方式

第一版推荐接入 AgentScope Java 真实调用 LLM，生成 `summary` 和 `questions`。

同时保留一个 `MockChunkEnrichmentGenerator` 作为本地兜底实现，但它不是主路径。

Mock 只用于：

1. 本地没有配置 API Key 时启动项目。
2. LLM 服务不可用时临时验证表结构和 MinIO 路径。
3. 开发阶段快速定位非模型调用问题。

真实 LLM 路径需要验证：

1. 表结构是否合理。
2. MinIO 路径是否合理。
3. 删除文件时增强文本是否能一起清理。
4. 后续 embedding 是否能读取 `embedding_text`。
5. AgentScope 模型调用是否能稳定返回结构化 JSON。

## 15. 第二种与第三种索引方式的关系

当前推荐第一版采用第二种方式：

```text
增强文本向量
```

也就是一个 chunk 只生成一条向量：

```text
embedding_type = HYBRID
```

这条向量来自：

```text
summary + questions + 原文
```

后续企业级版本可以升级为第三种：

```text
多向量索引
```

也就是一个 chunk 生成多条向量：

```text
embedding_type = ORIGINAL
embedding_type = SUMMARY
embedding_type = QUESTION
```

第一版表结构需要为 `embedding_type` 留口子，但不需要立即实现多向量。

## 16. 查询时如何保证返回原文

即使使用增强文本做 embedding，查询命中后也不返回增强文本。

查询链路应该是：

```text
用户问题
  -> embedding
  -> Milvus 检索
  -> 命中 chunk_id
  -> 查 knowledge_file_chunk
  -> 从 MinIO 读取原始 chunk 正文
  -> 原始 chunk 正文进入 rerank 或 LLM 上下文
```

因此摘要和模拟问题只是隐藏索引材料，不会默认出现在最终回答上下文中。

## 17. 删除与重建

删除文件时需要删除：

1. 原始文件对象。
2. chunk 正文对象。
3. chunk 元数据。
4. enrichment 增强文本对象。
5. enrichment 元数据。
6. 后续 Milvus 向量。

重建 chunk 时，需要先删除旧的 enrichment，再生成新的 enrichment。

原因：

1. chunk 内容变了，摘要和问题就失效。
2. chunk_id 或 chunk_index 可能变化。
3. embedding_text 也必须重新生成。

## 18. 推荐结论

第一版推荐：

1. 增加 `Chunk Enrichment` 层，位置在 chunk 后、embedding 前。
2. 每个 chunk 生成 `summary`、`questions_json` 和 `embedding_text`。
3. `summary` 和 `questions_json` 存 MySQL。
4. `embedding_text` 存 MinIO。
5. 查询召回后仍然读取原始 chunk 正文。
6. 第一版只实现 `HYBRID_TEXT` 增强策略。
7. 第一版通过 AgentScope Java 接入真实 LLM。
8. 保留 Mock 生成器作为本地兜底，不作为主路径。
9. 表结构预留 LLM 模型、prompt 版本和后续多向量索引字段。
