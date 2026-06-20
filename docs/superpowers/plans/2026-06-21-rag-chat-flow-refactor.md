# RAG 聊天流式编排重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `RagChatService#sendMessage` 和 `RagChatService#sendMessageStream` 中重复的 RAG 分支编排拆成可扩展的 Action Handler 结构，同时保留现有同步和流式接口行为。

**Architecture:** 使用“流程上下文 + Action 策略 + 回答生成策略”的轻量设计。`RagChatService` 继续作为业务入口，路由后交给 `ChatActionHandler` 负责准备引用上下文，最后由同步或流式生成方法输出回答。

**Tech Stack:** Java 21、Spring Boot、现有 Application Service 手动 Bean 配置、AgentScope RAG 生成器、Milvus 检索结果、MySQL 会话与引用记录。

---

### Task 1: 抽出聊天流程上下文与结果对象

**Files:**
- Modify: `backend/kb-application/src/main/java/com/example/kb/application/service/RagChatService.java`

- [x] **Step 1: 在 `RagChatService` 内新增内部 record**

新增 `ChatFlowContext`，字段包括 `conversationId`、`content`、`userMessage`、`recentMessages`、`reusableReferenceContext`、`routeResult`。

新增 `ChatActionResult`，字段包括 `referenceCandidates`、`retrievalTaskReports`、`answerReferences`。

- [x] **Step 2: 保持所有 record 使用显式类型**

不得使用 `var`，避免违反项目约定。

---

### Task 2: 抽出 Action Handler 策略

**Files:**
- Modify: `backend/kb-application/src/main/java/com/example/kb/application/service/RagChatService.java`

- [x] **Step 1: 新增内部接口 `ChatActionHandler`**

接口方法：

```java
private interface ChatActionHandler {

    boolean supports(RagRouterAction action);

    ChatActionResult handle(ChatFlowContext context);
}
```

- [x] **Step 2: 新增四个内部 Handler**

实现：

```java
private class NoKnowledgeChatActionHandler implements ChatActionHandler
private class SearchKnowledgeActionHandler implements ChatActionHandler
private class UsePreviousContextActionHandler implements ChatActionHandler
private class UsePreviousAndSearchActionHandler implements ChatActionHandler
```

每个 Handler 只负责准备引用和检索任务报告，不负责保存消息、不负责生成回答。

- [x] **Step 3: 新增 `resolveActionHandler` 方法**

根据归一化后的 `RagRouterAction` 找到对应 Handler。找不到时记录异常日志并抛出 `IllegalStateException`。

---

### Task 3: 合并同步和流式发送的公共编排

**Files:**
- Modify: `backend/kb-application/src/main/java/com/example/kb/application/service/RagChatService.java`

- [x] **Step 1: 新增 `prepareChatFlowContext`**

负责：
- 校验会话
- 保存用户消息
- 查询最近消息
- 查询上一轮可复用引用
- 查询知识库列表
- 调用 router
- 归一化 route result

- [x] **Step 2: 改造 `sendMessage`**

`sendMessage` 调用：
- `prepareChatFlowContext`
- `resolveActionHandler(...).handle(...)`
- `generateAnswer(...)`
- 保存助手消息、检索记录、引用记录、任务报告

- [x] **Step 3: 改造 `sendMessageStream`**

`sendMessageStream` 调用同一套上下文和 Handler，只在以下位置保留流式差异：
- route 后发送 `router`
- 引用准备后发送 `retrieval_done`
- 调用 `generateAnswerStream`
- 保存完成后发送 `answer_done`
- 最后发送 `references`

---

### Task 4: 编译验证

**Files:**
- No code file changes expected in this task.

- [x] **Step 1: 后端编译**

Run:

```bash
cd backend && /Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected: 命令退出码为 `0`。

- [x] **Step 2: 检查 Java 代码没有 `var`**

Run:

```bash
rg "\bvar\b" backend --glob "*.java"
```

Expected: 无输出。
