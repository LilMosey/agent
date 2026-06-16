# 文档解析层 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 Word、Markdown、TXT 文件的第一版解析层，把 MinIO 原始文件解析成统一的 `ParsedDocument / Section` 结构，并接入现有索引任务流水线。

**Architecture:** `IndexPipeline` 负责从文件元数据和 MinIO 读取原始文件，委托 `DocumentParserRegistry` 选择具体解析器，解析成功后更新文件和任务状态。`ParsedDocument` 只作为内存中间对象，不落库；后续 chunk、embedding、Milvus 写入在后续计划中实现。

**Tech Stack:** Java 21、Spring Boot 3.5、MyBatis-Plus、MinIO Java SDK、Apache POI `poi-ooxml`、Markdown 规则解析、TXT UTF-8 读取。

---

## 0. 范围约束

本计划只实现文档解析层，不实现以下内容：

1. 不做 chunk 拆分。
2. 不做 embedding。
3. 不写入 Milvus。
4. 不做 query、rerank、生成答案。
5. 不做 PDF 解析。
6. 不新增 Java 单元测试。
7. 不运行 npm 命令。

Java 编码约束：

1. 不使用 `var`。
2. 时间字段继续使用 `LocalDateTime`。
3. 所有重要入口、分支、出参、异常堆栈都打日志。
4. 解析失败时保存简短错误原因，完整堆栈只进日志。

## 1. 文件结构

### 新增文件

- `backend/kb-domain/src/main/java/com/example/kb/domain/model/ParsedDocument.java`
  - 解析后的文档模型。
- `backend/kb-domain/src/main/java/com/example/kb/domain/model/DocumentSection.java`
  - 解析后的章节模型。
- `backend/kb-application/src/main/java/com/example/kb/application/port/DocumentParser.java`
  - 解析器端口。
- `backend/kb-application/src/main/java/com/example/kb/application/port/DocumentParserRegistry.java`
  - 解析器选择器。
- `backend/kb-application/src/main/java/com/example/kb/application/port/DocumentParseCommand.java`
  - 解析命令对象。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/parser/DefaultDocumentParserRegistry.java`
  - 默认解析器注册表。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/parser/TxtDocumentParser.java`
  - TXT 解析器。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/parser/MarkdownDocumentParser.java`
  - Markdown 解析器。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/parser/DocxDocumentParser.java`
  - DOCX 解析器。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/parser/TextNormalizer.java`
  - 轻量文本标准化工具。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/parser/SectionIdGenerator.java`
  - 章节 ID 生成工具。

### 修改文件

- `backend/pom.xml`
  - 增加 `poi.version` 属性。
- `backend/kb-infrastructure/pom.xml`
  - 增加 `org.apache.poi:poi-ooxml` 依赖。
- `backend/kb-application/src/main/java/com/example/kb/application/port/KnowledgeFileRepository.java`
  - 增加更新文件解析状态的方法。
- `backend/kb-application/src/main/java/com/example/kb/application/service/KnowledgeFileIndexTaskService.java`
  - 当前可继续负责领取任务和更新任务状态。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisKnowledgeFileRepository.java`
  - 实现文件解析状态更新。
- `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/index/NoopIndexPipeline.java`
  - 替换为真正解析 pipeline，或改名为 `DefaultIndexPipeline`。
- `backend/kb-bootstrap/src/main/java/com/example/kb/bootstrap/ApplicationServiceConfiguration.java`
  - 如需手动 Bean 装配，在这里补充；优先使用 `@Component` 自动扫描。

---

## Task 1: 增加解析输出领域模型

**Files:**
- Create: `backend/kb-domain/src/main/java/com/example/kb/domain/model/ParsedDocument.java`
- Create: `backend/kb-domain/src/main/java/com/example/kb/domain/model/DocumentSection.java`

- [ ] **Step 1: 创建 `DocumentSection`**

内容要求：

```java
package com.example.kb.domain.model;

import java.util.Map;

public record DocumentSection(
        String id,
        String parentId,
        Integer level,
        String title,
        String content,
        Integer orderIndex,
        Map<String, String> metadata
) {
}
```

- [ ] **Step 2: 创建 `ParsedDocument`**

内容要求：

```java
package com.example.kb.domain.model;

import java.util.List;
import java.util.Map;

public record ParsedDocument(
        Long knowledgeBaseId,
        Long fileId,
        String filename,
        FileType fileType,
        String title,
        List<DocumentSection> sections,
        Map<String, String> metadata
) {
}
```

- [ ] **Step 3: 静态检查**

运行：

```bash
rg -n "\bvar\b|Instant|OffsetDateTime|ZonedDateTime|java\.util\.Date|Timestamp" backend/kb-domain
```

预期：无输出。

---

## Task 2: 增加解析端口和注册表端口

**Files:**
- Create: `backend/kb-application/src/main/java/com/example/kb/application/port/DocumentParseCommand.java`
- Create: `backend/kb-application/src/main/java/com/example/kb/application/port/DocumentParser.java`
- Create: `backend/kb-application/src/main/java/com/example/kb/application/port/DocumentParserRegistry.java`

- [ ] **Step 1: 创建解析命令对象**

内容要求：

```java
package com.example.kb.application.port;

import com.example.kb.domain.model.FileType;

import java.io.InputStream;

public record DocumentParseCommand(
        Long knowledgeBaseId,
        Long fileId,
        String filename,
        FileType fileType,
        String contentType,
        InputStream inputStream
) {
}
```

- [ ] **Step 2: 创建解析器接口**

内容要求：

```java
package com.example.kb.application.port;

import com.example.kb.domain.model.FileType;
import com.example.kb.domain.model.ParsedDocument;

public interface DocumentParser {

    boolean supports(FileType fileType);

    ParsedDocument parse(DocumentParseCommand command);
}
```

- [ ] **Step 3: 创建解析器注册表接口**

内容要求：

```java
package com.example.kb.application.port;

import com.example.kb.domain.model.FileType;

public interface DocumentParserRegistry {

    DocumentParser getParser(FileType fileType);
}
```

---

## Task 3: 增加 Apache POI 依赖

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/kb-infrastructure/pom.xml`

- [ ] **Step 1: 在父 POM 增加 POI 版本属性**

在 `<properties>` 中增加：

```xml
<poi.version>5.4.1</poi.version>
```

- [ ] **Step 2: 在 infrastructure 模块增加 DOCX 依赖**

在 `backend/kb-infrastructure/pom.xml` 的 `<dependencies>` 中增加：

```xml
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>${poi.version}</version>
</dependency>
```

- [ ] **Step 3: 用户执行 Maven 依赖校验**

由用户执行：

```bash
cd /Users/tangjie/javaai/agent/backend
mvn -q -DskipTests compile
```

预期：如果 Maven 能拉到依赖，编译继续到后续代码错误或成功；如果依赖拉取失败，再调整 `poi.version`。

---

## Task 4: 增加解析工具类

**Files:**
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/parser/TextNormalizer.java`
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/parser/SectionIdGenerator.java`

- [ ] **Step 1: 创建 `TextNormalizer`**

能力要求：

1. `normalizeLineBreaks(String text)`：把 `\r\n`、`\r` 统一成 `\n`。
2. `trimAndCollapseBlankLines(String text)`：去首尾空白，把 3 个及以上连续换行压成 2 个换行。
3. `removeControlChars(String text)`：去掉除 `\n`、`\t` 之外的 ISO 控制字符。

- [ ] **Step 2: 创建 `SectionIdGenerator`**

能力要求：

1. `rootId(Long fileId)` 返回 `file-{fileId}-section-root`。
2. `sectionId(Long fileId, int orderIndex)` 返回 `file-{fileId}-section-{orderIndex}`。

- [ ] **Step 3: 日志要求**

工具类不需要打日志，日志放在解析器入口和出参处。

---

## Task 5: 实现 TXT 解析器

**Files:**
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/parser/TxtDocumentParser.java`

- [ ] **Step 1: 创建 Spring 组件**

要求：

1. 使用 `@Component`。
2. 实现 `DocumentParser`。
3. `supports(FileType fileType)` 只支持 `FileType.TXT`。
4. 使用 `StandardCharsets.UTF_8` 读取输入流。

- [ ] **Step 2: 解析规则**

要求：

1. 读取全文。
2. 调用 `TextNormalizer` 做轻量标准化。
3. 创建一个根 `DocumentSection`。
4. 根章节 `title` 使用文件名。
5. 根章节 `content` 使用清理后的全文。
6. `ParsedDocument.title` 使用文件名。
7. `metadata` 至少包含 `parser=txt`。

- [ ] **Step 3: 日志要求**

必须记录：

1. 解析入口：`knowledgeBaseId`、`fileId`、`filename`。
2. 解析分支：TXT 单根章节。
3. 解析出参：章节数量、标题。
4. 异常堆栈。

---

## Task 6: 实现 Markdown 解析器

**Files:**
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/parser/MarkdownDocumentParser.java`

- [ ] **Step 1: 创建 Spring 组件**

要求：

1. 使用 `@Component`。
2. 实现 `DocumentParser`。
3. `supports(FileType fileType)` 只支持 `FileType.MARKDOWN`。
4. 使用 `StandardCharsets.UTF_8` 读取输入流。

- [ ] **Step 2: 标题识别规则**

要求：

1. 只识别 ATX 标题：`#` 到 `######`。
2. 标题前最多允许 3 个空格。
3. 代码块内的 `#` 不识别为标题。
4. 使用 ``` 切换 fenced code block 状态。
5. 标题层级映射为 `DocumentSection.level`。

- [ ] **Step 3: 章节树规则**

要求：

1. 没有标题前的正文放入默认根章节。
2. 当前标题的父章节是最近一个比它 level 小的章节。
3. 同级标题之间各自形成独立 section。
4. `content` 只保存当前标题下直属正文，不拼接子章节。
5. `title_path` 放入 section metadata，格式示例：`一级标题 > 二级标题`。

- [ ] **Step 4: 文档标题规则**

要求：

1. 优先使用第一个一级标题。
2. 没有一级标题时使用文件名。

- [ ] **Step 5: 日志要求**

必须记录：

1. Markdown 标题数量。
2. 默认根章节是否创建。
3. 最终 section 数量。
4. 异常堆栈。

---

## Task 7: 实现 DOCX 解析器

**Files:**
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/parser/DocxDocumentParser.java`

- [ ] **Step 1: 创建 Spring 组件**

要求：

1. 使用 `@Component`。
2. 实现 `DocumentParser`。
3. `supports(FileType fileType)` 只支持 `FileType.WORD`。
4. 使用 Apache POI `XWPFDocument` 读取 `.docx`。

- [ ] **Step 2: Word 标题样式识别**

标题识别要求：

1. 支持英文样式：`Heading 1` 到 `Heading 6`。
2. 支持中文样式：`标题 1` 到 `标题 6`。
3. 识别结果映射到 `DocumentSection.level`。
4. 未识别为标题的段落归入当前 section 正文。

- [ ] **Step 3: 默认根章节规则**

要求：

1. 文档开头没有标题时创建默认根章节。
2. 默认根章节标题使用文件名。
3. 后续遇到一级标题时，一级标题不挂在默认根章节下，作为根级章节。

- [ ] **Step 4: 表格文本提取**

第一版要求：

1. 遍历 `XWPFTable`。
2. 每一行使用 ` | ` 拼接单元格文本。
3. 表格文本追加到当前 section。
4. 不保留表格结构。

- [ ] **Step 5: 日志要求**

必须记录：

1. 段落数量。
2. 标题段落数量。
3. 表格数量。
4. 最终 section 数量。
5. 异常堆栈。

---

## Task 8: 实现解析器注册表

**Files:**
- Create: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/parser/DefaultDocumentParserRegistry.java`

- [ ] **Step 1: 创建 Spring 组件**

要求：

1. 使用 `@Component`。
2. 构造函数注入 `List<DocumentParser>`。
3. 实现 `DocumentParserRegistry`。

- [ ] **Step 2: 选择解析器**

规则：

1. 遍历所有 parser。
2. 找到第一个 `supports(fileType)` 为 true 的 parser。
3. 如果找不到，抛出 `IllegalArgumentException`，错误信息包含文件类型。

- [ ] **Step 3: 日志要求**

必须记录：

1. 注册 parser 数量。
2. 选择 parser 的文件类型。
3. 未找到 parser 的分支。

---

## Task 9: 增加文件解析状态更新端口

**Files:**
- Modify: `backend/kb-application/src/main/java/com/example/kb/application/port/KnowledgeFileRepository.java`
- Modify: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/persistence/repository/MybatisKnowledgeFileRepository.java`

- [ ] **Step 1: 修改应用端口**

在 `KnowledgeFileRepository` 中增加：

```java
void updateParseStatus(Long knowledgeBaseId, Long fileId, FileStatus fileStatus, String parseError, LocalDateTime updatedAt);
```

需要新增 imports：

```java
import com.example.kb.domain.model.FileStatus;
import java.time.LocalDateTime;
```

- [ ] **Step 2: 实现 MyBatis 更新**

在 `MybatisKnowledgeFileRepository` 中实现：

1. 根据 `knowledgeBaseId` 和 `fileId` 定位记录。
2. 更新 `file_status`、`parse_error`、`updated_at`。
3. 记录入参、分支、出参日志。
4. 如果更新行数为 0，记录 warn 日志。

---

## Task 10: 接入 IndexPipeline

**Files:**
- Modify: `backend/kb-infrastructure/src/main/java/com/example/kb/infrastructure/index/NoopIndexPipeline.java`

- [ ] **Step 1: 保留类名或改名选择**

推荐第一版直接把 `NoopIndexPipeline` 改造为真实解析 pipeline，避免同时存在两个 `IndexPipeline` Bean。

如果改名为 `DefaultIndexPipeline`，需要删除或停用 `NoopIndexPipeline` 的 `@Component`，避免 Bean 冲突。

- [ ] **Step 2: 注入依赖**

需要注入：

1. `KnowledgeFileRepository`
2. `ObjectStorage`
3. `DocumentParserRegistry`

- [ ] **Step 3: 执行流程**

`execute(KnowledgeFileIndexTask task)` 流程要求：

1. 根据 `task.knowledgeBaseId()` 和 `task.fileId()` 查询文件。
2. 文件不存在时返回 failed，并记录日志。
3. 更新文件状态为 `PARSING`。
4. 从 MinIO 读取原始文件输入流。
5. 构造 `DocumentParseCommand`。
6. 调用 parser 解析。
7. 解析成功后记录 `ParsedDocument.title` 和 section 数量。
8. 更新文件状态为 `READY`，`parseError` 置空。
9. 返回 `IndexPipelineResult.success("文档解析成功，sectionCount=...")`。

- [ ] **Step 4: 异常流程**

异常流程要求：

1. 捕获所有异常。
2. 记录完整异常堆栈。
3. 更新文件状态为 `PARSE_FAILED`。
4. `parseError` 保存简短错误原因。
5. 返回 `IndexPipelineResult.failed(errorMessage)`。

---

## Task 11: 编译与人工验证

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

- [ ] **Step 3: 启动后端**

由用户按当前项目方式启动后端。

- [ ] **Step 4: 上传 TXT 文件**

预期：

1. 文件上传成功。
2. `knowledge_file.file_status` 最终变为 `READY`。
3. `knowledge_file_index_task.status` 最终变为 `SUCCESS`。
4. 后端日志输出 `sectionCount=1`。

- [ ] **Step 5: 上传 Markdown 文件**

预期：

1. 文件上传成功。
2. `knowledge_file.file_status` 最终变为 `READY`。
3. `knowledge_file_index_task.status` 最终变为 `SUCCESS`。
4. 后端日志输出标题数量和 section 数量。

- [ ] **Step 6: 上传 DOCX 文件**

预期：

1. 文件上传成功。
2. `knowledge_file.file_status` 最终变为 `READY`。
3. `knowledge_file_index_task.status` 最终变为 `SUCCESS`。
4. 后端日志输出段落数量、标题段落数量、表格数量和 section 数量。

- [ ] **Step 7: 查询数据库确认状态**

执行：

```sql
SELECT id, original_filename, file_status, parse_error, updated_at
FROM knowledge_file
ORDER BY id DESC;

SELECT id, file_id, status, error_message, started_at, finished_at
FROM knowledge_file_index_task
ORDER BY id DESC;
```

预期：

1. 成功解析的文件 `parse_error` 为空。
2. 成功解析的任务 `error_message` 为成功摘要或为空，具体按实现保持一致。
3. 失败文件能看到简短失败原因。

---

## Task 12: 实施后的边界确认

**Files:**
- No code files.

- [ ] **Step 1: 确认 ParsedDocument 不落库**

检查实现中没有新增解析结果表，也没有把 `ParsedDocument` JSON 写入 MySQL 或 MinIO。

- [ ] **Step 2: 确认未提前实现 chunk**

检查实现中没有新增 chunk 表、chunk domain、chunk service、chunk parser 或 Milvus 写入逻辑。

- [ ] **Step 3: 确认未提前实现 query/rerank**

检查实现中没有新增查询接口、向量查询接口、rerank 组件或大模型生成调用。

---

## 计划自查

1. 设计文档中的统一结构、三种格式解析、状态流转、错误处理、扩展口子均有对应任务。
2. 本计划没有安排 Java 单元测试，符合当前项目约束。
3. 本计划没有安排 npm 命令。
4. 本计划没有要求持久化 `ParsedDocument`。
5. 本计划没有进入 chunk、embedding、Milvus、query、rerank。
6. 后续执行时如采用 SDD，建议按任务拆分：领域/端口、本地解析器、pipeline 接入、验证收尾。
