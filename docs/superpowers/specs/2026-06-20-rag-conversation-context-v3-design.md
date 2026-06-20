# RAG 会话上下文 V3 规格

## 背景

当前系统已经完成：

- 文件上传、解析、清洗、分块。
- chunk 增强摘要和模拟问题。
- DashScope embedding。
- Milvus dense 检索。
- Query 改写、HyDE、多 Query。
- BM25/Hybrid 代码主干。
- RRF 融合。
- RAG 流式回答。

V3 要解决的是多轮会话体验：用户连续追问时，系统需要理解最近对话，并在合适场景复用上一轮检索结果，而不是每一轮都像孤立问题。

## 目标

- 回答时携带最近会话上下文。
- 支持 `REUSE_LAST_CONTEXT` 真正复用上一轮引用。
- 知识库 chunk 只作为隐藏上下文输入大模型，不作为聊天消息展示。
- 前端仍然只展示用户消息、助手回答和引用来源。
- 为未来上下文压缩预留结构，但本期不做复杂压缩。
- 每个会话上下文隔离，切换会话不会串用上下文。

## 非目标

- 不做长期记忆。
- 不做用户画像记忆。
- 不做 Agent memory。
- 不做复杂上下文压缩算法。
- 不做 Rerank。
- 不做 RAGAS 评估。
- 不在前端展示拼接后的 Prompt。
- 不在前端展示隐藏知识库正文。

## 核心概念

### 最近消息上下文

最近消息上下文是当前会话中最近若干条用户和助手消息。

第一版建议：

```text
最近 6 条消息
```

也就是大约 3 轮对话。

它用于：

- Query 改写。
- Router 判断 `FOLLOW_UP`、`REUSE_LAST_CONTEXT`。
- 大模型最终回答。

### 隐藏知识上下文

隐藏知识上下文是 RAG 检索出来的 chunk 正文。

它用于：

- 作为大模型回答依据。
- 支持引用来源展示。

它不用于：

- 作为聊天消息落库。
- 直接展示在前端聊天气泡。
- 暴露给用户作为 Prompt 内容。

### 上一轮引用上下文

上一轮引用上下文是同一个会话中最近一次有引用记录的 RAG 检索结果。

当 Router 返回 `REUSE_LAST_CONTEXT` 时，本轮不重新检索知识库，而是复用上一轮引用。

典型场景：

```text
用户：差旅报销标准是什么？
助手：...
用户：整理成表格
```

第二轮应复用第一轮引用内容。

## 总体链路

```text
用户发送消息
  -> 保存用户消息
  -> 查询最近会话消息
  -> Router 基于当前问题、知识库描述、最近消息判断 action
     -> NO_KB：普通聊天
     -> SEARCH_KB：执行检索
     -> REUSE_LAST_CONTEXT：读取上一轮引用
  -> 组装 AnswerContext
     -> 当前问题
     -> 最近消息上下文
     -> 当前检索引用或上一轮复用引用
  -> 流式生成回答
  -> 保存 assistant 消息
  -> 保存 retrieval
  -> 保存引用记录
```

## Router 调整

Router 输入需要增加最近消息上下文。

当前输入：

```text
当前用户问题
知识库列表
```

V3 输入：

```text
当前用户问题
知识库列表
最近消息上下文
```

Router 判断规则：

- 用户问企业制度、文档事实、知识库内容：`SEARCH_KB`。
- 用户普通聊天、翻译、改写、不依赖知识库：`NO_KB`。
- 用户要求“总结一下”“整理成表格”“换一种说法”“刚才依据是什么”，且最近一轮有引用：`REUSE_LAST_CONTEXT`。
- 如果无法确定知识库 ID，不查全部，返回 `NO_KB`。

## Answer Prompt 调整

回答 Prompt 需要分三层：

```text
最近会话上下文
知识库引用上下文
当前用户问题
```

如果没有知识库引用：

- 仍然可以使用最近会话上下文做普通聊天。
- 不要声称查阅知识库。

如果有知识库引用：

- 企业事实严格基于知识库引用。
- 最近消息只用于理解追问和表达风格。
- 不要把最近消息当作企业制度事实来源。
- 回答要自然，不要说“根据引用 1”。

## 数据读取策略

### 最近消息

使用现有 `conversation_message` 表。

按 `message_order` 升序读取当前会话消息，取最近 N 条。

需要排除当前刚保存的用户消息时，由调用方控制。

### 上一轮引用

使用现有表：

- `conversation_retrieval`
- `conversation_retrieval_reference`
- `knowledge_file_chunk`
- MinIO chunk 正文

查询策略：

```text
当前 conversation_id
按 conversation_retrieval.id 倒序
找到最近一个有 reference 的 retrieval
读取 reference 对应 chunk
回查 MinIO 正文
```

复用时需要保存新的本轮 retrieval 和 reference 记录，便于本轮回答也能溯源。

## 配置

建议新增：

```yaml
rag:
  context:
    recent-message-limit: 6
    reuse-last-context-enabled: true
```

含义：

- `recent-message-limit`：回答和 Router 使用的最近消息数量。
- `reuse-last-context-enabled`：是否启用上一轮引用复用。

## 前端行为

前端无需展示隐藏上下文。

前端变化很小：

- 继续展示聊天消息。
- 继续展示引用来源。
- 对 `REUSE_LAST_CONTEXT` 复用来的引用，仍按普通引用展示。

不新增：

- Prompt 展示。
- 隐藏知识上下文展示。
- 最近消息上下文展示。

## 日志要求

需要记录：

- 最近消息上下文数量。
- Router action。
- 是否触发 `REUSE_LAST_CONTEXT`。
- 复用的上一轮 retrievalId。
- 复用引用数量。
- Answer Prompt 使用的历史消息数量和引用数量。

异常需要记录堆栈。

## 验证方式

### 普通多轮追问

```text
用户：差旅报销标准是什么？
用户：那西安呢？
```

预期：

- 第二轮 Query 改写能利用最近消息上下文。
- 回答能理解“那”指代差旅报销标准。

### 复用上一轮引用

```text
用户：差旅报销标准是什么？
用户：整理成表格
```

预期：

- 第二轮 Router 返回或归一化为 `REUSE_LAST_CONTEXT`。
- 第二轮不执行新的 Milvus 检索任务。
- 第二轮仍保存 retrieval/reference。
- 第二轮引用来源来自上一轮引用。

### 普通聊天

```text
用户：你好
用户：帮我写一句欢迎语
```

预期：

- 不查询知识库。
- 可以带最近消息上下文自然回答。
- 不产生检索任务。

## V4 预留

V4 再处理：

- Rerank。
- RAGAS/评估集。
- Bad Case 分析。
- 策略对比。
- 上下文压缩效果评估。
