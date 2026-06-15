# 文档解析层设计

## 1. 背景

当前项目已经完成文件管理能力，并补充了上传文件后自动创建索引任务、定时任务扫描待处理任务的基础骨架。下一阶段需要开始建设 RAG 离线索引链路，但本阶段只讨论和设计“文档解析层”，不实现 chunk、embedding、Milvus 写入、查询、rerank 和答案生成。

文档解析层的作用是把 Word、Markdown、TXT 三类文件解析成统一的中间结构。后续清洗、chunk、embedding 都只依赖这个统一结构，而不直接关心原始文件格式差异。

## 2. 本阶段目标

本阶段目标如下：

1. 定义解析层统一输出结构。
2. 保留文档章节层级关系，为后续 chunk 策略留出空间。
3. 定义 Word、Markdown、TXT 三种格式的第一版解析策略。
4. 明确解析失败时的任务状态、文件状态和错误记录方式。
5. 为后续 PDF、HTML、Excel 等格式扩展保留接口。

## 3. 本阶段不做的内容

本阶段不做以下内容：

1. 不做 chunk 拆分策略。
2. 不做 embedding 模型选型和向量生成。
3. 不写入 Milvus。
4. 不做 query、召回、rerank、生成答案。
5. 不做 PDF 解析，只在接口层面保留扩展口子。
6. 不引入 OCR。
7. 不做复杂版面还原，例如 Word 表格、页眉页脚、脚注、批注、图片内容识别。

## 4. 总体架构

解析层建议位于应用服务和基础设施之间：

```text
定时任务
  -> 索引任务服务
    -> IndexPipeline
      -> 文档读取
      -> 文档解析
      -> 输出 ParsedDocument
      -> 后续清洗、chunk、embedding 留待下一阶段
```

其中：

1. `IndexPipeline` 负责编排离线索引流程。
2. `DocumentParser` 负责定义统一解析接口。
3. `TxtDocumentParser`、`MarkdownDocumentParser`、`DocxDocumentParser` 分别处理具体文件格式。
4. `DocumentParserRegistry` 根据文件类型选择解析器。
5. `ParsedDocument` 和 `Section` 是解析层统一输出模型。

## 5. 统一输出结构

### 5.1 ParsedDocument

`ParsedDocument` 表示一个原始文件解析后的完整文档。

建议字段如下：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| knowledgeBaseId | Long | 知识库 ID |
| fileId | Long | 文件 ID |
| filename | String | 原始文件名 |
| fileType | FileType | 文件类型 |
| title | String | 文档标题，优先来自一级标题或文件名 |
| sections | List<Section> | 章节列表，保留层级结构 |
| metadata | Map<String, String> | 文档级元数据 |

说明：

1. `sections` 是解析结果的核心。
2. `metadata` 第一版只保留轻量信息，例如文件扩展名、内容来源、解析器名称。
3. 不在 `ParsedDocument` 中直接保存 chunk 信息，避免解析层和 chunk 层耦合。

### 5.2 Section

`Section` 表示文档中的一个章节或一个内容块。

建议字段如下：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | String | 解析阶段生成的章节 ID |
| parentId | String | 父章节 ID，根章节为空 |
| level | Integer | 章节层级，根层级为 1 |
| title | String | 章节标题，没有标题时可为空 |
| content | String | 章节正文 |
| orderIndex | Integer | 在文档中的顺序 |
| metadata | Map<String, String> | 章节级元数据 |

说明：

1. `id` 不使用数据库主键，只是解析结果内部的稳定标识。
2. `parentId` 用于保留章节层级关系。
3. `level` 用于表达标题层级，例如 Markdown 的 `#`、`##`、`###`。
4. `content` 只放当前章节自身正文，不强行拼接子章节正文。
5. `orderIndex` 用于保留原始阅读顺序，后续 chunk 可以按顺序遍历。
6. `metadata` 可保存标题路径、Word 样式名、段落序号等轻量信息。

## 6. 为什么保留章节层级

保留章节层级的主要收益如下：

1. 后续 chunk 可以按自然章节边界切分，减少把一个完整语义单元切断的概率。
2. chunk metadata 可以带上标题路径，例如 `产品手册 > 部署说明 > Docker 启动`，提升检索结果可解释性。
3. 后续可以实现 Small-to-Big 策略：检索时使用小 chunk，生成时回补同章节或父章节上下文。
4. 后续做结构化召回、章节过滤、目录导航时，不需要重新解析原始文件。
5. 对 Word 和 Markdown 这类天然有结构的文档，可以最大化保留原始作者的组织意图。

## 7. 三种格式解析策略

### 7.1 TXT 解析

TXT 没有可靠的标题语义，第一版采用保守策略：

1. 读取全文。
2. 统一换行符。
3. 按连续空行识别段落边界。
4. 生成一个根 `Section`。
5. 根 `Section` 的 `title` 使用文件名。
6. 根 `Section` 的 `content` 保存清理后的全文。

第一版不尝试从 TXT 中推断标题层级，避免误判。

### 7.2 Markdown 解析

Markdown 具备明确标题语法，第一版按标题构建章节树：

1. 识别 ATX 标题：`#` 到 `######`。
2. 标题层级映射为 `Section.level`。
3. 标题下方到下一个同级或更高级标题之前的内容，归属当前 `Section.content`。
4. 没有标题前的内容，放入一个默认根章节。
5. 文档标题优先使用第一个一级标题；如果没有一级标题，则使用文件名。
6. 保留代码块内容，但标题识别需要避开代码块内部的 `#`。

第一版不解析 Markdown 表格、链接、图片的结构化语义，只保留原始文本。

### 7.3 DOCX 解析

Word 文档第一版使用段落为基础进行解析：

1. 读取 `.docx` 段落。
2. 优先根据 Word 标题样式识别章节，例如 `Heading 1`、`Heading 2`、`标题 1`、`标题 2`。
3. 如果段落不是标题，则归入当前章节正文。
4. 如果文档开头没有任何标题，则创建默认根章节，标题使用文件名。
5. 文档标题优先使用第一个一级标题；如果没有一级标题，则使用文件名。
6. 表格内容第一版可以按行提取为纯文本并追加到当前章节，但不做单元格结构还原。

第一版不处理页眉、页脚、脚注、批注、图片 OCR 和复杂版面。

## 8. 解析接口设计

解析接口建议保持简单：

```text
DocumentParser
  supports(fileType)
  parse(command) -> ParsedDocument
```

`parse` 入参需要包含：

1. 知识库 ID。
2. 文件 ID。
3. 文件名。
4. 文件类型。
5. 文件输入流。
6. 内容类型。

解析器只负责“格式到统一结构”的转换，不负责：

1. 从 MinIO 下载文件。
2. 修改数据库状态。
3. 创建 chunk。
4. 调用 embedding。
5. 写入向量数据库。

这些动作由 `IndexPipeline` 或更上层应用服务编排。

## 9. 状态流转

文件状态建议第一版按以下方式流转：

```text
PENDING_PARSE -> PARSING -> READY
PENDING_PARSE -> PARSING -> PARSE_FAILED
```

索引任务状态建议第一版按以下方式流转：

```text
PENDING -> RUNNING -> SUCCESS
PENDING -> RUNNING -> FAILED
```

当前 `SKIPPED` 状态只用于 pipeline 尚未实现时的过渡状态。解析层接入后，正常解析成功应进入 `SUCCESS`，解析失败进入 `FAILED`。

由于本阶段还不做 chunk 和 embedding，`SUCCESS` 的含义是“文档解析成功并得到 ParsedDocument”，不是“完成向量索引”。

## 10. 错误处理

解析失败时：

1. 记录完整异常日志。
2. `knowledge_file.parse_error` 保存面向用户的简短错误原因。
3. `knowledge_file.file_status` 更新为 `PARSE_FAILED`。
4. `knowledge_file_index_task.status` 更新为 `FAILED`。
5. `knowledge_file_index_task.error_message` 保存失败原因。

错误原因需要避免过长，数据库字段上限内保存摘要，完整堆栈只进日志。

## 11. 清洗边界

解析层可以做轻量标准化：

1. 统一换行符。
2. 去掉不可见控制字符。
3. 去掉首尾空白。
4. 合并过多连续空行。

解析层不做深度清洗：

1. 不删除业务内容。
2. 不做同义词替换。
3. 不做摘要。
4. 不做敏感词处理。
5. 不做 chunk 长度控制。

深度清洗应放在后续独立的文本清洗层。

## 12. 扩展口子

后续新增格式时，只需要新增对应 `DocumentParser` 实现，并注册到 `DocumentParserRegistry`。

预留扩展格式：

1. PDF：后续需要区分文字层 PDF 和扫描件 PDF；扫描件需要 OCR。
2. HTML：需要去除脚本、样式，只保留正文和标题结构。
3. Excel：需要按工作表、表头、行列关系设计结构化解析策略。
4. JSON：需要按字段路径保留结构。

## 13. 验收标准

本设计进入编码后，第一版验收标准如下：

1. 上传 TXT 文件后，解析任务可以生成一个根章节。
2. 上传 Markdown 文件后，可以按标题生成多级章节。
3. 上传 DOCX 文件后，可以按 Word 标题样式生成章节。
4. 解析成功时，文件状态从 `PENDING_PARSE` 变为 `READY`。
5. 解析失败时，文件状态变为 `PARSE_FAILED`，任务状态变为 `FAILED`。
6. 所有解析入口、分支、出参和异常堆栈都记录日志。
7. Java 代码不使用 `var`。
8. 时间字段继续使用 `LocalDateTime`。
9. 不新增 Java 单元测试。

## 14. 推荐结论

第一版采用“统一 ParsedDocument + Section 层级结构”的解析设计。它比直接输出纯文本稍微多一点结构成本，但能给后续 chunk、检索解释、Small-to-Big 上下文回补留下空间，也不会过早引入复杂版面解析。

本阶段编码时应优先保证链路清晰、结构稳定、失败可观测，不追求复杂解析效果。
