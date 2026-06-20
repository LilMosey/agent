# RAG 会话上下文 V3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 RAG 查询链路中加入最近会话上下文，并让 `REUSE_LAST_CONTEXT` 真正复用上一轮引用。

**Architecture:** V3 新增独立的会话上下文服务，负责读取最近消息和上一轮引用；RagChatService 继续作为主流程编排者。Router、Query 改写和 Answer 生成接收最近消息上下文，知识库 chunk 仍只作为隐藏上下文参与回答。

**Tech Stack:** Java、Spring Boot、MyBatis-Plus、MyBatis XML、MySQL、MinIO、Milvus、AgentScope Java、React、TypeScript、Ant Design。

---

## Task 1: 新增会话上下文配置

**Files:**
- Create: `backend/kb-application/src/main/java/com/example/kb/application/service/RagContextProperties.java`
- Modify: `backend/kb-bootstrap/src/main/resources/application.yml`
- Modify: `backend/kb-bootstrap/src/main/java/com/example/kb/bootstrap/ApplicationServiceConfiguration.java`

- [ ] 新增应用层纯配置对象 `RagContextProperties`，不引入 Spring Boot 注解，避免 application 模块依赖 Spring Boot。

```java
package com.example.kb.application.service;

public record RagContextProperties(
        Integer recentMessageLimit,
        Boolean reuseLastContextEnabled
) {

    public int safeRecentMessageLimit() {
        return recentMessageLimit == null ? 6 : recentMessageLimit;
    }

    public boolean isReuseLastContextEnabled() {
        return reuseLastContextEnabled == null || reuseLastContextEnabled;
    }
}
```

- [ ] 在 `application.yml` 增加配置。

```yaml
rag:
  context:
    recent-message-limit: 6
    reuse-last-context-enabled: true
```

- [ ] 在 `ApplicationServiceConfiguration` 通过 `Environment` 创建 `RagContextProperties` Bean。

```java
@Bean
public RagContextProperties ragContextProperties(Environment environment) {
    return new RagContextProperties(
            environment.getProperty("rag.context.recent-message-limit", Integer.class),
            environment.getProperty("rag.context.reuse-last-context-enabled", Boolean.class)
    );
}
```

- [ ] 后端编译通过。

```bash
cd /Users/tangjie/javaai/agent/backend
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

## Task 2: 扩展 Repository 端口读取上一轮引用

**Files:**
- Modify: `backend/kb-application/src/main/java/com/example/kb/application/port/ConversationRetrievalRepository.java`
- Modify: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisConversationRetrievalRepository.java`

- [ ] 在 `ConversationRetrievalRepository` 增加方法。

```java
Optional<ConversationRetrieval> findLatestWithReferencesByConversationId(Long conversationId);
```

- [ ] 在 `MybatisConversationRetrievalRepository` 使用 MyBatis-Plus 查询最近一条有引用的 retrieval。

查询条件：

```text
conversation_retrieval.conversation_id = conversationId
exists conversation_retrieval_reference
order by conversation_retrieval.id desc
limit 1
```

- [ ] 如果 MyBatis-Plus LambdaWrapper 表达 exists 不清晰，新增 XML 查询，Mapper 只声明方法，不写 SQL 注解。

- [ ] 后端编译通过。

## Task 3: 新增会话上下文服务

**Files:**
- Create: `backend/kb-application/src/main/java/com/example/kb/application/service/RagConversationContextService.java`
- Modify: `backend/kb-bootstrap/src/main/java/com/example/kb/bootstrap/ApplicationServiceConfiguration.java`

- [ ] 新增 `RagConversationContextService`。

职责：

```text
读取最近消息
读取上一轮 retrieval
读取上一轮引用
把上一轮引用还原为可回答上下文
```

- [ ] 构造函数注入：

```java
ConversationMessageRepository conversationMessageRepository
ConversationRetrievalRepository conversationRetrievalRepository
ConversationRetrievalReferenceRepository conversationRetrievalReferenceRepository
DocumentChunkRepository documentChunkRepository
KnowledgeFileRepository knowledgeFileRepository
ChunkContentStorage chunkContentStorage
RagContextProperties ragContextProperties
```

- [ ] 暴露方法：

```java
List<ConversationMessage> recentMessages(Long conversationId, Long excludedMessageId)
Optional<ReusableReferenceContext> latestReusableReferenceContext(Long conversationId)
```

- [ ] `recentMessages` 逻辑：

```text
查询当前会话全部消息
排除 excludedMessageId
按 messageOrder 保持原顺序
取最后 recent-message-limit 条
```

- [ ] `latestReusableReferenceContext` 逻辑：

```text
如果 reuse-last-context-enabled=false，返回 Optional.empty()
查询最近一条有引用的 retrieval
查询其 reference
回查 chunk、file、MinIO 正文
组装 ReferenceCandidate 等价数据
```

- [ ] 日志记录：

```text
recentMessageCount
latestRetrievalId
referenceCount
missingChunkCount
missingFileCount
```

- [ ] 后端编译通过。

## Task 4: Router 接入最近消息上下文

**Files:**
- Modify: `backend/kb-application/src/main/java/com/example/kb/application/port/RagRouter.java`
- Modify: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/rag/RagPromptBuilder.java`
- Modify: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/rag/AgentScopeRagRouter.java`
- Modify: `backend/kb-application/src/main/java/com/example/kb/application/service/RagChatService.java`

- [ ] 修改 `RagRouter.RouteCommand`，增加：

```java
List<ConversationMessage> recentMessages
```

- [ ] 修改 `RagPromptBuilder.buildRouterPrompt`，在 Prompt 中加入最近消息上下文。

Prompt 规则必须包含：

```text
如果用户是在要求总结、改写、整理、换格式、追问刚才内容，并且最近对话有知识库回答痕迹，优先返回 REUSE_LAST_CONTEXT。
如果无法确定应该查询哪个知识库，返回 NO_KB，不要选择全部知识库。
```

- [ ] 修改 `RagChatService.route`，传入最近消息。

- [ ] 后端编译通过。

## Task 5: Answer 接入最近消息上下文

**Files:**
- Modify: `backend/kb-application/src/main/java/com/example/kb/application/port/RagAnswerGenerator.java`
- Modify: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/rag/RagPromptBuilder.java`
- Modify: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/rag/AgentScopeRagAnswerGenerator.java`
- Modify: `backend/kb-application/src/main/java/com/example/kb/application/service/RagChatService.java`

- [ ] 修改 `RagAnswerGenerator.AnswerCommand`，增加：

```java
List<ConversationMessage> recentMessages
```

- [ ] 修改 `RagPromptBuilder.buildAnswerPrompt`，分三段：

```text
最近会话上下文
知识库引用上下文
当前用户问题
```

- [ ] Prompt 规则：

```text
最近会话上下文只用于理解追问和表达风格。
企业制度事实必须来自知识库引用上下文。
如果没有知识库引用，不要声称查阅知识库。
不要在回答正文中提到引用编号、上下文编号或资料编号。
```

- [ ] 修改 `generateAnswer` 和 `generateAnswerStream` 调用，传入最近消息。

- [ ] 后端编译通过。

## Task 6: 真正支持 REUSE_LAST_CONTEXT

**Files:**
- Modify: `backend/kb-application/src/main/java/com/example/kb/application/service/RagChatService.java`
- Modify: `backend/kb-application/src/main/java/com/example/kb/application/service/RagConversationContextService.java`

- [ ] 修改 `RagChatService.sendMessage` 和 `sendMessageStream` 分支。

目标逻辑：

```text
NO_KB:
  不查知识库，使用最近消息普通回答

SEARCH_KB:
  执行 V2 检索增强

REUSE_LAST_CONTEXT:
  读取上一轮引用
  如果读取成功，复用引用回答
  如果读取失败，按普通聊天回答，不查全部知识库
```

- [ ] 移除 V2 中 `REUSE_LAST_CONTEXT` 降级为 `SEARCH_KB` 的逻辑。

- [ ] 复用上一轮引用时，本轮仍保存新的：

```text
conversation_retrieval
conversation_retrieval_reference
```

- [ ] 复用上一轮引用时，不新增检索任务。

- [ ] 后端编译通过。

## Task 7: 前端保持隐藏上下文

**Files:**
- No code files expected.

- [ ] 确认前端不展示最近消息上下文。
- [ ] 确认前端不展示知识库 chunk 正文。
- [ ] 确认前端只展示：

```text
用户消息
助手回答
引用来源列表
```

- [ ] 如果后端 SSE 事件结构不变，前端不修改。

## Task 8: 验证

**Files:**
- No code files.

- [ ] 后端编译。

```bash
cd /Users/tangjie/javaai/agent/backend
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

- [ ] Java 约束扫描。

```bash
cd /Users/tangjie/javaai/agent
rg "\\bvar\\b" backend --glob "*.java"
```

预期：无输出。

- [ ] Mapper SQL 注解扫描。

```bash
cd /Users/tangjie/javaai/agent
rg "@Insert|@Update|@Delete|@Select" backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/mapper
```

预期：无新增输出。

- [ ] 手工验证普通多轮追问。

```text
用户：差旅报销标准是什么？
用户：那西安呢？
```

预期：

```text
第二轮能理解追问含义。
```

- [ ] 手工验证复用上一轮引用。

```text
用户：差旅报销标准是什么？
用户：整理成表格
```

预期：

```text
第二轮不新增 BM25/Dense 检索任务。
第二轮 conversation_retrieval_reference 有引用记录。
回答正常流式输出。
```

- [ ] 手工验证普通聊天。

```text
用户：你好
用户：帮我写一句欢迎语
```

预期：

```text
不产生检索任务。
不声称查阅知识库。
```

## 实施注意

- Java 代码不要使用 `var`。
- 不新增单元测试。
- Mapper 不写 SQL 注解，复杂 SQL 使用 XML。
- 所有异常日志需要带堆栈。
- 不把隐藏上下文写入 `conversation_message.content`。
