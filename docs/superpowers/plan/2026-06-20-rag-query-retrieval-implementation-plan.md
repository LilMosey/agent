# RAG 查询检索第一版 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现第一版聊天窗口式 RAG 查询：会话隔离、知识库 Router、Dense Retrieval、Milvus 检索、MySQL/MinIO 溯源、大模型回答和前端引用展示。

**Architecture:** 后端沿用当前多模块 Maven 分层。`kb-domain` 定义会话、消息、路由、引用等领域对象；`kb-application` 编排发送消息和 RAG 查询流程；`kb-infrastructure` 提供 MySQL、MinIO、Milvus Search、AgentScope Router/Answer 生成实现；`kb-api` 暴露会话和消息接口；前端新增聊天页并复用现有 API client。

**Tech Stack:** Java、Spring Boot、MyBatis-Plus、MyBatis XML、MySQL、MinIO、Milvus Java SDK、DashScope Embedding、AgentScope Java、React、TypeScript、Ant Design。

---

## 参考规格

- 规格文档：`docs/superpowers/specs/2026-06-20-rag-query-retrieval-design.md`
- 本计划遵循用户约束：
  - Java 代码不使用 `var`。
  - `createdAt`、`updatedAt` 使用 `LocalDateTime`。
  - 不主动编写 Java 单元测试。
  - 所有新增 Markdown 使用中文。
  - 后端所有分支、入参出参、异常堆栈需要打日志。

## 本期范围

本期实现：

- 多轮会话窗口。
- 消息保存。
- 每轮先调用知识库 Router。
- Router 根据知识库名称和描述返回 `action`、`knowledgeBaseIds`、`queryIntent`、`confidence`、`reason`。
- 第一版 Dense Retrieval。
- Milvus 按 `knowledge_base_id in (...)` 过滤检索。
- 根据 `chunk_id` 回查 MySQL，读取 MinIO 原始 chunk 正文。
- 大模型基于引用内容生成回答。
- 保存检索记录和引用记录。
- 前端展示会话列表、消息、输入框和引用来源。

本期不实现：

- HyDE。
- Query 改写。
- BM25、关键词检索、混合检索。
- Rerank。
- 上下文压缩。
- Agent 长期记忆。
- 流式输出。

## 文件结构总览

### 后端新增文件

- `backend/kb-domain/src/main/java/com/example/kb/domain/model/Conversation.java`
- `backend/kb-domain/src/main/java/com/example/kb/domain/model/ConversationMessage.java`
- `backend/kb-domain/src/main/java/com/example/kb/domain/model/ConversationRetrieval.java`
- `backend/kb-domain/src/main/java/com/example/kb/domain/model/ConversationRetrievalReference.java`
- `backend/kb-domain/src/main/java/com/example/kb/domain/model/MessageRole.java`
- `backend/kb-domain/src/main/java/com/example/kb/domain/model/RagRouterAction.java`
- `backend/kb-domain/src/main/java/com/example/kb/domain/model/QueryIntent.java`
- `backend/kb-application/src/main/java/com/example/kb/application/port/ConversationRepository.java`
- `backend/kb-application/src/main/java/com/example/kb/application/port/ConversationMessageRepository.java`
- `backend/kb-application/src/main/java/com/example/kb/application/port/ConversationRetrievalRepository.java`
- `backend/kb-application/src/main/java/com/example/kb/application/port/ConversationRetrievalReferenceRepository.java`
- `backend/kb-application/src/main/java/com/example/kb/application/port/RagRouter.java`
- `backend/kb-application/src/main/java/com/example/kb/application/port/RagAnswerGenerator.java`
- `backend/kb-application/src/main/java/com/example/kb/application/port/VectorIndexSearcher.java`
- `backend/kb-application/src/main/java/com/example/kb/application/service/ConversationService.java`
- `backend/kb-application/src/main/java/com/example/kb/application/service/RagChatService.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/entity/ConversationEntity.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/entity/ConversationMessageEntity.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/entity/ConversationRetrievalEntity.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/entity/ConversationRetrievalReferenceEntity.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/mapper/ConversationMapper.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/mapper/ConversationMessageMapper.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/mapper/ConversationRetrievalMapper.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/mapper/ConversationRetrievalReferenceMapper.java`
- `backend/kb-infrastructure/src/main/resources/mapper/ConversationMapper.xml`
- `backend/kb-infrastructure/src/main/resources/mapper/ConversationMessageMapper.xml`
- `backend/kb-infrastructure/src/main/resources/mapper/ConversationRetrievalMapper.xml`
- `backend/kb-infrastructure/src/main/resources/mapper/ConversationRetrievalReferenceMapper.xml`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisConversationRepository.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisConversationMessageRepository.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisConversationRetrievalRepository.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisConversationRetrievalReferenceRepository.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/rag/AgentScopeRagRouter.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/rag/AgentScopeRagAnswerGenerator.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/rag/RagPromptBuilder.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/rag/RagProperties.java`
- `backend/kb-api/src/main/java/com/example/kb/api/controller/ConversationController.java`
- `backend/kb-api/src/main/java/com/example/kb/api/dto/ConversationDtos.java`

### 后端修改文件

- `backend/kb-application/src/main/java/com/example/kb/application/port/DocumentChunkRepository.java`
- `backend/kb-application/src/main/java/com/example/kb/application/port/KnowledgeFileRepository.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisDocumentChunkRepository.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisKnowledgeFileRepository.java`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/mapper/DocumentChunkMapper.java`
- `backend/kb-infrastructure/src/main/resources/mapper/DocumentChunkMapper.xml`
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/vector/MilvusVectorIndexStore.java`
- `backend/kb-bootstrap/src/main/java/com/example/kb/bootstrap/ApplicationServiceConfiguration.java`
- `backend/kb-bootstrap/src/main/resources/application.yml`
- `backend/kb-infrastructure/src/main/resources/db/schema.sql`

### 前端新增文件

- `frontend/src/api/conversationApi.ts`
- `frontend/src/components/ConversationList.tsx`
- `frontend/src/components/ChatMessageList.tsx`
- `frontend/src/components/ChatInput.tsx`
- `frontend/src/components/ReferenceList.tsx`
- `frontend/src/pages/ConversationPage.tsx`

### 前端修改文件

- `frontend/src/App.tsx`
- `frontend/src/types/domain.ts`
- `frontend/src/styles.css`

## 数据库脚本

需要在 `backend/kb-infrastructure/src/main/resources/db/schema.sql` 增加以下表。实际执行时用户需要在 DBeaver 或 MySQL 客户端中手动执行。

```sql
CREATE TABLE IF NOT EXISTS conversation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS conversation_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    role VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    message_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_message_conversation_id (conversation_id),
    KEY idx_message_conversation_order (conversation_id, message_order)
);

CREATE TABLE IF NOT EXISTS conversation_retrieval (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    query_text TEXT NOT NULL,
    action VARCHAR(64) NOT NULL,
    knowledge_base_ids_json JSON NULL,
    query_intent VARCHAR(64) NOT NULL,
    confidence DECIMAL(5, 4) NOT NULL DEFAULT 0,
    reason VARCHAR(1024) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_retrieval_conversation_id (conversation_id),
    KEY idx_retrieval_message_id (message_id)
);

CREATE TABLE IF NOT EXISTS conversation_retrieval_reference (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_retrieval_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    chunk_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    title_path VARCHAR(1024) NULL,
    score DECIMAL(10, 6) NOT NULL DEFAULT 0,
    content_preview VARCHAR(512) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_reference_retrieval_id (conversation_retrieval_id),
    KEY idx_reference_chunk_id (chunk_id),
    KEY idx_reference_file_id (file_id)
);
```

## Task 1: 领域模型和应用端口

**Files:**
- Create: `backend/kb-domain/src/main/java/com/example/kb/domain/model/Conversation.java`
- Create: `backend/kb-domain/src/main/java/com/example/kb/domain/model/ConversationMessage.java`
- Create: `backend/kb-domain/src/main/java/com/example/kb/domain/model/ConversationRetrieval.java`
- Create: `backend/kb-domain/src/main/java/com/example/kb/domain/model/ConversationRetrievalReference.java`
- Create: `backend/kb-domain/src/main/java/com/example/kb/domain/model/MessageRole.java`
- Create: `backend/kb-domain/src/main/java/com/example/kb/domain/model/RagRouterAction.java`
- Create: `backend/kb-domain/src/main/java/com/example/kb/domain/model/QueryIntent.java`
- Create: `backend/kb-application/src/main/java/com/example/kb/application/port/ConversationRepository.java`
- Create: `backend/kb-application/src/main/java/com/example/kb/application/port/ConversationMessageRepository.java`
- Create: `backend/kb-application/src/main/java/com/example/kb/application/port/ConversationRetrievalRepository.java`
- Create: `backend/kb-application/src/main/java/com/example/kb/application/port/ConversationRetrievalReferenceRepository.java`
- Create: `backend/kb-application/src/main/java/com/example/kb/application/port/RagRouter.java`
- Create: `backend/kb-application/src/main/java/com/example/kb/application/port/RagAnswerGenerator.java`
- Create: `backend/kb-application/src/main/java/com/example/kb/application/port/VectorIndexSearcher.java`

- [ ] **Step 1: 创建枚举**

创建 `MessageRole`：

```java
package com.example.kb.domain.model;

public enum MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}
```

创建 `RagRouterAction`：

```java
package com.example.kb.domain.model;

public enum RagRouterAction {
    NO_KB,
    SEARCH_KB,
    REUSE_LAST_CONTEXT
}
```

创建 `QueryIntent`：

```java
package com.example.kb.domain.model;

public enum QueryIntent {
    FACT_QA,
    SUMMARY,
    FORMAT_CONVERT,
    FOLLOW_UP,
    CHAT
}
```

- [ ] **Step 2: 创建领域模型**

创建 `Conversation`：

```java
package com.example.kb.domain.model;

import java.time.LocalDateTime;

public record Conversation(
        Long id,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
```

创建 `ConversationMessage`：

```java
package com.example.kb.domain.model;

import java.time.LocalDateTime;

public record ConversationMessage(
        Long id,
        Long conversationId,
        MessageRole role,
        String content,
        Integer messageOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
```

创建 `ConversationRetrieval`：

```java
package com.example.kb.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ConversationRetrieval(
        Long id,
        Long conversationId,
        Long messageId,
        String queryText,
        RagRouterAction action,
        List<Long> knowledgeBaseIds,
        QueryIntent queryIntent,
        BigDecimal confidence,
        String reason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
```

创建 `ConversationRetrievalReference`：

```java
package com.example.kb.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ConversationRetrievalReference(
        Long id,
        Long conversationRetrievalId,
        Long knowledgeBaseId,
        Long fileId,
        Long chunkId,
        Integer chunkIndex,
        String titlePath,
        BigDecimal score,
        String contentPreview,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
```

- [ ] **Step 3: 创建应用端口**

`ConversationRepository`：

```java
package com.example.kb.application.port;

import com.example.kb.domain.model.Conversation;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository {

    Conversation save(Conversation conversation);

    Optional<Conversation> findById(Long id);

    List<Conversation> findAllOrderByUpdatedAtDesc();

    void updateTitle(Long id, String title);
}
```

`ConversationMessageRepository`：

```java
package com.example.kb.application.port;

import com.example.kb.domain.model.ConversationMessage;

import java.util.List;

public interface ConversationMessageRepository {

    ConversationMessage save(ConversationMessage message);

    List<ConversationMessage> findByConversationId(Long conversationId);

    Integer nextMessageOrder(Long conversationId);
}
```

`ConversationRetrievalRepository`：

```java
package com.example.kb.application.port;

import com.example.kb.domain.model.ConversationRetrieval;

public interface ConversationRetrievalRepository {

    ConversationRetrieval save(ConversationRetrieval retrieval);
}
```

`ConversationRetrievalReferenceRepository`：

```java
package com.example.kb.application.port;

import com.example.kb.domain.model.ConversationRetrievalReference;

import java.util.List;

public interface ConversationRetrievalReferenceRepository {

    void saveBatch(List<ConversationRetrievalReference> references);

    List<ConversationRetrievalReference> findByRetrievalId(Long retrievalId);
}
```

`RagRouter`：

```java
package com.example.kb.application.port;

import com.example.kb.domain.model.QueryIntent;
import com.example.kb.domain.model.RagRouterAction;

import java.math.BigDecimal;
import java.util.List;

public interface RagRouter {

    RouteResult route(RouteCommand command);

    record RouteCommand(
            String userQuestion,
            List<KnowledgeBaseOption> knowledgeBases
    ) {
    }

    record KnowledgeBaseOption(
            Long id,
            String name,
            String description
    ) {
    }

    record RouteResult(
            RagRouterAction action,
            List<Long> knowledgeBaseIds,
            QueryIntent queryIntent,
            BigDecimal confidence,
            String reason
    ) {
    }
}
```

`RagAnswerGenerator`：

```java
package com.example.kb.application.port;

import java.util.List;

public interface RagAnswerGenerator {

    AnswerResult generate(AnswerCommand command);

    record AnswerCommand(
            String userQuestion,
            List<ReferenceContext> references
    ) {
    }

    record ReferenceContext(
            Integer referenceNo,
            String fileName,
            String titlePath,
            Integer chunkIndex,
            String content
    ) {
    }

    record AnswerResult(
            String content,
            String provider,
            String model
    ) {
    }
}
```

`VectorIndexSearcher`：

```java
package com.example.kb.application.port;

import java.math.BigDecimal;
import java.util.List;

public interface VectorIndexSearcher {

    SearchResult search(SearchCommand command);

    record SearchCommand(
            List<Long> knowledgeBaseIds,
            List<Float> queryVector,
            int topK
    ) {
    }

    record SearchResult(
            List<SearchHit> hits
    ) {
    }

    record SearchHit(
            Long knowledgeBaseId,
            Long fileId,
            Long chunkId,
            Integer chunkIndex,
            BigDecimal score
    ) {
    }
}
```

- [ ] **Step 4: 编译检查**

Run:

```bash
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected:

```text
BUILD SUCCESS
```

如果编译失败，先修正类型、包名、导入和 `var` 问题。

## Task 2: MySQL 表、Entity、Mapper 和 Repository

**Files:**
- Modify: `backend/kb-infrastructure/src/main/resources/db/schema.sql`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/entity/ConversationEntity.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/entity/ConversationMessageEntity.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/entity/ConversationRetrievalEntity.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/entity/ConversationRetrievalReferenceEntity.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/mapper/ConversationMapper.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/mapper/ConversationMessageMapper.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/mapper/ConversationRetrievalMapper.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/mapper/ConversationRetrievalReferenceMapper.java`
- Create: `backend/kb-infrastructure/src/main/resources/mapper/ConversationMapper.xml`
- Create: `backend/kb-infrastructure/src/main/resources/mapper/ConversationMessageMapper.xml`
- Create: `backend/kb-infrastructure/src/main/resources/mapper/ConversationRetrievalMapper.xml`
- Create: `backend/kb-infrastructure/src/main/resources/mapper/ConversationRetrievalReferenceMapper.xml`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisConversationRepository.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisConversationMessageRepository.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisConversationRetrievalRepository.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisConversationRetrievalReferenceRepository.java`

- [ ] **Step 1: 更新 schema.sql**

在 `knowledge_file_index_task` 表后追加“数据库脚本”章节中的四张表。

- [ ] **Step 2: 创建 Entity**

Entity 使用 MyBatis-Plus 注解和当前项目已有命名风格。字段使用 `LocalDateTime`，不要使用 `var`。

示例 `ConversationEntity`：

```java
package com.example.kb.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("conversation")
public class ConversationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
```

其他 Entity 按表字段创建，`knowledge_base_ids_json` 在 Entity 中使用 `String` 存储 JSON。

- [ ] **Step 3: 创建 Mapper 接口**

每个 Mapper 继承 `BaseMapper<Entity>`。不要在 Mapper 接口中写 `@Insert` 这类 SQL 注解。

示例：

```java
package com.example.kb.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.kb.infrastructure.persistence.entity.ConversationEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
}
```

- [ ] **Step 4: 创建 XML Mapper**

XML 中放自定义查询和批量插入 SQL。

`ConversationMessageMapper.xml` 至少包含：

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper
        PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.example.kb.infrastructure.persistence.mapper.ConversationMessageMapper">
    <select id="selectNextMessageOrder" resultType="java.lang.Integer">
        SELECT COALESCE(MAX(message_order), 0) + 1
        FROM conversation_message
        WHERE conversation_id = #{conversationId}
    </select>
</mapper>
```

对应 Mapper 接口增加方法签名：

```java
Integer selectNextMessageOrder(Long conversationId);
```

`ConversationRetrievalReferenceMapper.xml` 增加批量插入：

```xml
<insert id="insertBatch">
    INSERT INTO conversation_retrieval_reference (
        conversation_retrieval_id,
        knowledge_base_id,
        file_id,
        chunk_id,
        chunk_index,
        title_path,
        score,
        content_preview,
        created_at,
        updated_at
    )
    VALUES
    <foreach collection="list" item="item" separator=",">
        (
            #{item.conversationRetrievalId},
            #{item.knowledgeBaseId},
            #{item.fileId},
            #{item.chunkId},
            #{item.chunkIndex},
            #{item.titlePath},
            #{item.score},
            #{item.contentPreview},
            #{item.createdAt},
            #{item.updatedAt}
        )
    </foreach>
</insert>
```

- [ ] **Step 5: 创建 Repository 实现**

Repository 负责领域对象和 Entity 转换。保存时统一补充 `createdAt`、`updatedAt`。

`MybatisConversationRepository.save` 示例逻辑：

```java
LocalDateTime now = LocalDateTime.now();
ConversationEntity entity = new ConversationEntity();
entity.setTitle(conversation.title());
entity.setCreatedAt(now);
entity.setUpdatedAt(now);
conversationMapper.insert(entity);
return new Conversation(entity.getId(), entity.getTitle(), entity.getCreatedAt(), entity.getUpdatedAt());
```

JSON 转换使用 `ObjectMapper`，不要手写字符串拼接。

- [ ] **Step 6: 编译检查**

Run:

```bash
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected:

```text
BUILD SUCCESS
```

## Task 3: Chunk 回查和文件回查能力

**Files:**
- Modify: `backend/kb-application/src/main/java/com/example/kb/application/port/DocumentChunkRepository.java`
- Modify: `backend/kb-application/src/main/java/com/example/kb/application/port/KnowledgeFileRepository.java`
- Modify: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/mapper/DocumentChunkMapper.java`
- Modify: `backend/kb-infrastructure/src/main/resources/mapper/DocumentChunkMapper.xml`
- Modify: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisDocumentChunkRepository.java`
- Modify: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisKnowledgeFileRepository.java`

- [ ] **Step 1: DocumentChunkRepository 增加批量查询**

增加：

```java
List<DocumentChunk> findByIds(List<Long> ids);
```

实现时如果入参为空，直接返回空列表并记录日志。

- [ ] **Step 2: DocumentChunkMapper 增加 XML 查询**

在 XML 中增加：

```xml
<select id="selectByIds" resultType="com.example.kb.infrastructure.persistence.entity.DocumentChunkEntity">
    SELECT
        id,
        knowledge_base_id,
        file_id,
        section_id,
        parent_section_id,
        chunk_index,
        chunk_strategy,
        chunk_size,
        chunk_overlap,
        title_path,
        content_preview,
        content_hash,
        content_size,
        start_offset,
        end_offset,
        storage_bucket,
        storage_object_key,
        created_at,
        updated_at
    FROM knowledge_file_chunk
    WHERE id IN
    <foreach collection="ids" item="id" open="(" separator="," close=")">
        #{id}
    </foreach>
</select>
```

Mapper 接口增加：

```java
List<DocumentChunkEntity> selectByIds(List<Long> ids);
```

- [ ] **Step 3: KnowledgeFileRepository 确认已有 findById**

如果已有 `findById(Long id)`，直接复用。

如果没有，增加：

```java
Optional<KnowledgeFile> findById(Long id);
```

- [ ] **Step 4: 编译检查**

Run:

```bash
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected:

```text
BUILD SUCCESS
```

## Task 4: Milvus Search 端口实现

**Files:**
- Modify: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/vector/MilvusVectorIndexStore.java`
- Modify: `backend/kb-bootstrap/src/main/java/com/example/kb/bootstrap/ApplicationServiceConfiguration.java`

- [ ] **Step 1: MilvusVectorIndexStore 实现 VectorIndexSearcher**

类声明改为：

```java
public class MilvusVectorIndexStore implements VectorIndexWriter, VectorIndexCleaner, VectorIndexSearcher {
}
```

增加 import：

```java
import com.example.kb.application.port.VectorIndexSearcher;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import java.math.BigDecimal;
import java.util.Collections;
```

- [ ] **Step 2: 实现 search 方法**

搜索逻辑：

```java
@Override
public SearchResult search(SearchCommand command) {
    log.info("Milvus 向量检索入参: knowledgeBaseIds={}, topK={}, vectorDimension={}",
            command.knowledgeBaseIds(), command.topK(), command.queryVector().size());
    if (command.knowledgeBaseIds().isEmpty()) {
        log.info("Milvus 向量检索分支: knowledgeBaseIds 为空");
        return new SearchResult(List.of());
    }
    if (!hasCollection()) {
        log.warn("Milvus 向量检索分支: collection 不存在, collection={}", milvusProperties.collectionName());
        return new SearchResult(List.of());
    }
    String filter = buildKnowledgeBaseFilter(command.knowledgeBaseIds());
    SearchReq searchReq = SearchReq.builder()
            .databaseName(milvusProperties.database())
            .collectionName(milvusProperties.collectionName())
            .annsField(VECTOR_FIELD)
            .topK(command.topK())
            .filter(filter)
            .data(Collections.singletonList(command.queryVector()))
            .outputFields(List.of("knowledge_base_id", "file_id", "chunk_id", "chunk_index"))
            .build();
    SearchResp searchResp = milvusClient.search(searchReq);
    List<SearchHit> hits = convertSearchHits(searchResp);
    log.info("Milvus 向量检索出参: knowledgeBaseIds={}, hitCount={}", command.knowledgeBaseIds(), hits.size());
    return new SearchResult(hits);
}
```

`buildKnowledgeBaseFilter` 对单个 ID 生成 `knowledge_base_id == 2`，多个 ID 生成 `knowledge_base_id in [2, 5]`。

`convertSearchHits` 需要根据当前 Milvus SDK 的 `SearchResp` 结构取出字段和 score。实现时必须用本地 Maven jar 的实际 API 编译确认。

- [ ] **Step 3: 编译检查**

Run:

```bash
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected:

```text
BUILD SUCCESS
```

## Task 5: RAG Router 和回答生成器

**Files:**
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/rag/RagProperties.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/rag/RagPromptBuilder.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/rag/AgentScopeRagRouter.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/rag/AgentScopeRagAnswerGenerator.java`
- Modify: `backend/kb-bootstrap/src/main/resources/application.yml`
- Modify: `backend/kb-bootstrap/src/main/java/com/example/kb/bootstrap/ApplicationServiceConfiguration.java`

- [ ] **Step 1: 增加配置**

在 `application.yml` 的 `rag` 下增加：

```yaml
  query:
    router-model: qwen-plus
    answer-model: qwen-plus
    router-confidence-threshold: 0.6
    retrieval-top-k: 10
    context-top-k: 5
```

API Key 沿用现有 AgentScope/LLM 配置，不在普通 yaml 中写明文 key。

- [ ] **Step 2: 创建 RagProperties**

```java
package com.example.kb.infrastructure.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "rag.query")
public record RagProperties(
        String routerModel,
        String answerModel,
        BigDecimal routerConfidenceThreshold,
        Integer retrievalTopK,
        Integer contextTopK
) {
}
```

- [ ] **Step 3: 创建 Prompt Builder**

`RagPromptBuilder` 提供两个方法：

```java
String buildRouterPrompt(String userQuestion, List<RagRouter.KnowledgeBaseOption> knowledgeBases);

String buildAnswerPrompt(String userQuestion, List<RagAnswerGenerator.ReferenceContext> references);
```

Router Prompt 必须要求模型返回 JSON，并限定：

```json
{
  "action": "SEARCH_KB",
  "knowledgeBaseIds": [1],
  "queryIntent": "FACT_QA",
  "confidence": 0.8,
  "reason": "原因"
}
```

Answer Prompt 必须要求：

- 只基于引用内容回答。
- 不编造金额、日期、制度、审批流程。
- 不足时回答“知识库中没有找到明确依据”。
- 回答中使用“引用 1、引用 2”标注来源。

- [ ] **Step 4: 实现 AgentScopeRagRouter**

实现逻辑：

- 打日志：入参问题、知识库数量。
- 构造 Router Prompt。
- 调用 AgentScope。
- 清洗模型返回的 JSON。
- 使用 `ObjectMapper` 解析。
- 将非法 action、非法 intent、空知识库 ID 兜底为 `SEARCH_KB` + 全部可选知识库。
- 如果 `confidence < routerConfidenceThreshold` 且 action 不是 `SEARCH_KB`，改为 `SEARCH_KB`。
- 异常时记录堆栈，返回 `SEARCH_KB` + 全部可选知识库 + `confidence=0`。

- [ ] **Step 5: 实现 AgentScopeRagAnswerGenerator**

实现逻辑：

- 打日志：问题长度、引用数量。
- 构造 Answer Prompt。
- 调用 AgentScope。
- 返回回答文本、provider、model。
- 异常时记录堆栈并抛出 `IllegalStateException("RAG 回答生成失败: " + e.getMessage(), e)`。

- [ ] **Step 6: 注册 Bean**

在 `ApplicationServiceConfiguration` 注册：

```java
@Bean
public RagRouter ragRouter(
        RagProperties ragProperties,
        RagPromptBuilder ragPromptBuilder,
        ObjectMapper objectMapper
) {
    return new AgentScopeRagRouter(ragProperties, ragPromptBuilder, objectMapper);
}

@Bean
public RagAnswerGenerator ragAnswerGenerator(
        RagProperties ragProperties,
        RagPromptBuilder ragPromptBuilder
) {
    return new AgentScopeRagAnswerGenerator(ragProperties, ragPromptBuilder);
}
```

同时确保 `RagProperties` 被 `@EnableConfigurationProperties` 或现有配置机制加载。

- [ ] **Step 7: 编译检查**

Run:

```bash
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected:

```text
BUILD SUCCESS
```

## Task 6: 会话服务和 RAG 查询编排

**Files:**
- Create: `backend/kb-application/src/main/java/com/example/kb/application/service/ConversationService.java`
- Create: `backend/kb-application/src/main/java/com/example/kb/application/service/RagChatService.java`
- Modify: `backend/kb-bootstrap/src/main/java/com/example/kb/bootstrap/ApplicationServiceConfiguration.java`

- [ ] **Step 1: 实现 ConversationService**

职责：

- 创建会话。
- 查询会话列表。
- 查询会话消息。

创建会话标题默认：

```text
新会话
```

保存后返回领域对象。

- [ ] **Step 2: 实现 RagChatService 主流程**

构造函数注入：

- `ConversationRepository`
- `ConversationMessageRepository`
- `ConversationRetrievalRepository`
- `ConversationRetrievalReferenceRepository`
- `KnowledgeBaseRepository`
- `KnowledgeFileRepository`
- `DocumentChunkRepository`
- `ChunkContentStorage`
- `RagRouter`
- `EmbeddingGenerator`
- `VectorIndexSearcher`
- `RagAnswerGenerator`

主方法：

```java
public SendMessageResult sendMessage(Long conversationId, String content)
```

流程：

1. 校验会话存在。
2. 保存用户消息。
3. 查询全部知识库，转为 Router option。
4. 调用 Router。
5. 将 `REUSE_LAST_CONTEXT` 降级为 `SEARCH_KB`。
6. 如果 `NO_KB`，调用普通回答生成器。本期可用 `RagAnswerGenerator` 空引用调用，Prompt Builder 需要支持无引用普通回答。
7. 如果 `SEARCH_KB`，使用当前问题生成 embedding。
8. 调用 Milvus Search。
9. 按 `chunk_id` 去重，截取 `contextTopK`。
10. 批量查 chunk。
11. 根据 chunk 的 `storageBucket` 和 `storageObjectKey` 从 MinIO 读取原始正文。
12. 查询文件名。
13. 组装引用上下文。
14. 调用大模型回答。
15. 保存助手消息。
16. 保存 conversation_retrieval。
17. 保存 conversation_retrieval_reference。
18. 返回回答、router、references。

- [ ] **Step 3: 定义 SendMessageResult**

可以作为 `RagChatService` 内部 record：

```java
public record SendMessageResult(
        ConversationMessage assistantMessage,
        RagRouter.RouteResult router,
        List<ReferenceResult> references
) {
}

public record ReferenceResult(
        Integer referenceNo,
        Long knowledgeBaseId,
        Long fileId,
        String fileName,
        Long chunkId,
        Integer chunkIndex,
        String titlePath,
        BigDecimal score,
        String contentPreview
) {
}
```

- [ ] **Step 4: 注册 Bean**

在 `ApplicationServiceConfiguration` 注册 `ConversationService` 和 `RagChatService`。

- [ ] **Step 5: 编译检查**

Run:

```bash
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected:

```text
BUILD SUCCESS
```

## Task 7: REST API

**Files:**
- Create: `backend/kb-api/src/main/java/com/example/kb/api/dto/ConversationDtos.java`
- Create: `backend/kb-api/src/main/java/com/example/kb/api/controller/ConversationController.java`

- [ ] **Step 1: 创建 DTO**

`ConversationDtos` 包含：

- `ConversationResponse`
- `ConversationMessageResponse`
- `CreateConversationResponse`
- `SendMessageRequest`
- `SendMessageResponse`
- `RouterResponse`
- `ReferenceResponse`

`SendMessageRequest`：

```java
public record SendMessageRequest(
        String content
) {
}
```

`ReferenceResponse`：

```java
public record ReferenceResponse(
        Integer referenceNo,
        Long knowledgeBaseId,
        Long fileId,
        String fileName,
        Long chunkId,
        Integer chunkIndex,
        String titlePath,
        BigDecimal score,
        String contentPreview
) {
}
```

- [ ] **Step 2: 创建 Controller**

接口：

```text
POST /api/conversations
GET /api/conversations
GET /api/conversations/{conversationId}/messages
POST /api/conversations/{conversationId}/messages
```

Controller 每个接口打日志：

- 入参。
- 出参数量或 ID。
- 异常由全局异常处理器处理。

- [ ] **Step 3: 编译检查**

Run:

```bash
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected:

```text
BUILD SUCCESS
```

## Task 8: 前端 API 和类型

**Files:**
- Modify: `frontend/src/types/domain.ts`
- Create: `frontend/src/api/conversationApi.ts`

- [ ] **Step 1: 增加类型**

在 `domain.ts` 中增加：

```ts
export interface Conversation {
  id: number;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export interface ConversationMessage {
  id: number;
  conversationId: number;
  role: 'USER' | 'ASSISTANT' | 'SYSTEM';
  content: string;
  messageOrder: number;
  createdAt: string;
  updatedAt: string;
}

export interface RouterResult {
  action: 'NO_KB' | 'SEARCH_KB' | 'REUSE_LAST_CONTEXT';
  knowledgeBaseIds: number[];
  queryIntent: 'FACT_QA' | 'SUMMARY' | 'FORMAT_CONVERT' | 'FOLLOW_UP' | 'CHAT';
  confidence: number;
  reason: string;
}

export interface RetrievalReference {
  referenceNo: number;
  knowledgeBaseId: number;
  fileId: number;
  fileName: string;
  chunkId: number;
  chunkIndex: number;
  titlePath?: string;
  score: number;
  contentPreview?: string;
}

export interface SendMessageResponse {
  messageId: number;
  content: string;
  router: RouterResult;
  references: RetrievalReference[];
}
```

- [ ] **Step 2: 创建 conversationApi**

封装：

```ts
export async function createConversation(): Promise<Conversation>
export async function listConversations(): Promise<Conversation[]>
export async function listConversationMessages(conversationId: number): Promise<ConversationMessage[]>
export async function sendConversationMessage(conversationId: number, content: string): Promise<SendMessageResponse>
```

复用 `frontend/src/api/client.ts` 里的 HTTP 客户端风格。

- [ ] **Step 3: 前端类型检查**

由用户执行：

```bash
cd /Users/tangjie/javaai/agent/frontend
npm run build
```

Expected:

```text
构建成功，无 TypeScript 错误
```

## Task 9: 前端聊天页面

**Files:**
- Create: `frontend/src/components/ConversationList.tsx`
- Create: `frontend/src/components/ChatMessageList.tsx`
- Create: `frontend/src/components/ChatInput.tsx`
- Create: `frontend/src/components/ReferenceList.tsx`
- Create: `frontend/src/pages/ConversationPage.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/styles.css`

- [ ] **Step 1: ConversationList**

职责：

- 展示会话列表。
- 支持新建会话。
- 点击切换当前会话。

空列表时显示“暂无会话”。

- [ ] **Step 2: ChatMessageList**

职责：

- 展示用户消息和助手消息。
- 用户消息右侧展示。
- 助手消息左侧展示。
- 助手消息下方挂载 `ReferenceList`。

- [ ] **Step 3: ReferenceList**

职责：

- 展示引用序号、文件名、标题路径、chunk 序号、命中分数、内容预览。
- 默认折叠或紧凑展示。

引用展示格式：

```text
引用 1 · 差旅制度.docx · chunk 3 · 0.8321
标题路径：制度汇编 / 差旅报销 / 住宿标准
预览：非一线城市住宿补贴上限为...
```

- [ ] **Step 4: ChatInput**

职责：

- 文本输入。
- 发送按钮。
- 发送中禁用按钮。
- 空内容不发送。

- [ ] **Step 5: ConversationPage**

职责：

- 首次进入加载会话列表。
- 如果没有会话，自动创建一个会话。
- 选择会话时加载消息。
- 发送消息后先追加用户消息占位，再用接口返回的助手消息更新列表。
- 接口失败时展示错误提示。

- [ ] **Step 6: 修改 App.tsx**

第一版可以在现有知识库管理页面和聊天页面之间提供简单切换。

如果当前 `App.tsx` 没有路由，可以使用 Ant Design Tabs：

```text
知识库管理
RAG 查询
```

- [ ] **Step 7: 前端构建**

由用户执行：

```bash
cd /Users/tangjie/javaai/agent/frontend
npm run build
```

Expected:

```text
构建成功，无 TypeScript 错误
```

## Task 10: 手动联调验证

**Files:**
- No code files.

- [ ] **Step 1: 用户执行数据库脚本**

用户在 DBeaver 或 MySQL 客户端执行 `schema.sql` 中新增的四张表。

检查：

```sql
SHOW TABLES LIKE 'conversation%';
```

Expected:

```text
conversation
conversation_message
conversation_retrieval
conversation_retrieval_reference
```

- [ ] **Step 2: 用户启动 Docker 服务**

在项目根目录执行：

```bash
docker compose up -d
```

检查：

```bash
docker ps
```

Expected:

```text
agent-mysql、agent-minio、agent-milvus、agent-attu 均为 Up
```

- [ ] **Step 3: 用户启动后端**

用户用 IDE 或 Maven 启动 Spring Boot。

Expected:

```text
后端启动成功，无 Bean 创建失败，无 Milvus 连接失败
```

- [ ] **Step 4: 用户启动前端**

用户执行：

```bash
cd /Users/tangjie/javaai/agent/frontend
npm run start
```

Expected:

```text
Vite 启动成功，可以访问本地页面
```

- [ ] **Step 5: 验证查询闭环**

前置条件：

- 至少有一个知识库。
- 至少有一个文件状态为 `READY`。
- Milvus collection `enterprise_kb_chunk` 中存在对应向量。

操作：

1. 打开 RAG 查询页面。
2. 新建会话。
3. 输入一个知识库内的问题。
4. 等待回答。

Expected:

- 页面展示助手回答。
- 页面展示引用来源。
- `conversation_message` 有 USER 和 ASSISTANT 两条记录。
- `conversation_retrieval` 有一条 Router 和查询记录。
- `conversation_retrieval_reference` 有引用 chunk 记录。
- 后端日志能看到 Router、Embedding、Milvus Search、MySQL 回查、MinIO 读取、大模型回答生成日志。

- [ ] **Step 6: 验证 NO_KB**

输入：

```text
你好，帮我把“今天状态不错”改得正式一点
```

Expected:

- Router 允许返回 `NO_KB`。
- 不触发 Milvus 检索日志。
- 页面正常展示普通回答。

如果 Router 低置信度触发兜底 `SEARCH_KB`，也接受，但需要在日志中看到兜底原因。

- [ ] **Step 7: 验证知识库选择**

准备两个知识库，问题只属于其中一个知识库。

Expected:

- Router 返回对应的 `knowledgeBaseIds`。
- Milvus Search filter 只包含这些知识库 ID。

## Task 11: 收尾检查

**Files:**
- No code files.

- [ ] **Step 1: Java var 检查**

Run:

```bash
rg "\bvar\b" backend --glob "*.java"
```

Expected:

```text
无输出
```

- [ ] **Step 2: SQL 注解检查**

Run:

```bash
rg "@Insert|@Update|@Delete|@Select" backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/mapper
```

Expected:

```text
无新增 SQL 注解；自定义 SQL 在 XML 中
```

- [ ] **Step 3: 后端编译**

Run:

```bash
/Users/tangjie/software/apache-maven-3.6.3/bin/mvn -q -Dmaven.repo.local=/Users/tangjie/mvnRepo -DskipTests compile
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 4: 前端构建**

由用户执行：

```bash
cd /Users/tangjie/javaai/agent/frontend
npm run build
```

Expected:

```text
构建成功
```

- [ ] **Step 5: Git 状态检查**

Run:

```bash
git status --short
```

Expected:

```text
只包含本次 RAG 查询检索相关文件变更
```

## 实现顺序建议

1. Task 1：领域模型和应用端口。
2. Task 2：MySQL 表、Entity、Mapper 和 Repository。
3. Task 3：Chunk 回查和文件回查能力。
4. Task 4：Milvus Search 端口实现。
5. Task 5：RAG Router 和回答生成器。
6. Task 6：会话服务和 RAG 查询编排。
7. Task 7：REST API。
8. Task 8：前端 API 和类型。
9. Task 9：前端聊天页面。
10. Task 10：手动联调验证。
11. Task 11：收尾检查。

每完成一个后端 Task，都执行一次 Maven compile。每完成一个前端 Task，由用户执行一次 `npm run build`。

