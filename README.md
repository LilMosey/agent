# Enterprise KB 企业知识库 RAG 系统

Enterprise KB 是一个企业知识库管理与 RAG 对话 Demo 项目。它的目标是把企业内部文档上传、解析、切分、增强、向量化、检索和问答串成一条完整链路，为后续 Agent 应用预留扩展空间。

当前版本重点覆盖两块能力：

- 知识库管理：创建知识库、上传文件、查看文件状态、删除文件、下载文件。
- RAG 对话：创建会话、发送问题、自动判断是否查询知识库、检索相关 chunk、生成回答、展示引用来源。

项目不包含登录认证，适合作为本地学习、技术验证和 RAG 工程化 Demo。

## 项目定位

这个项目不是一个单纯的文件管理系统，而是一个面向 RAG 的知识库底座。

文件上传后，系统会通过后台索引任务完成以下流程：

1. 读取原始文件。
2. 解析为统一的 `ParsedDocument` 结构。
3. 执行文本清洗。
4. 按上传时选择的策略切分为 chunk。
5. 调用大模型生成 chunk 摘要和模拟问题。
6. 生成 embedding。
7. 写入 Milvus 向量库。
8. 查询时结合 Dense Retrieval、BM25、Query Rewrite、HyDE、Multi Query、Rerank 等能力完成检索增强。

后续可以继续扩展 Agent、工具调用、长期记忆、上下文压缩、RAG 自动评估等能力。

## 当前功能

### 知识库管理

- 新建、编辑、删除知识库。
- 同一知识库下管理多个文件。
- 上传文件时可配置 chunk 策略、块大小、重叠长度。
- 支持文件类型：
  - DOCX
  - Markdown
  - TXT
- 当前暂不支持 PDF，但架构上已预留扩展口。
- 文件上传后自动创建索引任务。
- 定时任务扫描 `PENDING` 状态任务并构建索引。
- 文件删除时同步删除：
  - MySQL 元数据
  - MinIO 文件与 chunk 内容
  - Milvus 向量数据

### 文档处理

- 解析器按文件类型分发。
- 文档解析后统一转成 `ParsedDocument`。
- 文本清洗独立成步骤。
- chunk 支持多种策略：
  - 固定大小切分
  - 按章节切分
  - 递归切分
- chunk 原文存储在 MinIO。
- chunk 元数据存储在 MySQL。
- chunk 增强信息支持摘要和模拟问题。
- embedding 文本优先使用增强文本，增强失败时回退到原始 chunk。

### RAG 查询

- 支持多轮会话。
- 支持会话重命名和软删除。
- 支持流式回答。
- 支持基于知识库描述的路由判断。
- 支持不查知识库时直接普通聊天。
- 支持复用上一轮 RAG 上下文。
- 支持补充检索。
- 支持检索引用来源展示。

当前已实现的检索增强能力包括：

- 原始 Query 稠密向量检索
- Query 改写
- HyDE
- Multi Query
- BM25
- RRF 融合
- Rerank

## 技术栈

### 后端

- Java 21
- Spring Boot 3.5.0
- Maven 多模块工程
- MyBatis-Plus
- MySQL 8.4
- MinIO
- Milvus 2.6.9
- AgentScope Java `2.0.0-RC3`
- DashScope Java SDK
- Apache POI

后端模块：

```text
backend/
  kb-domain          领域模型
  kb-application     应用服务、端口接口、业务编排
  kb-api             Controller、接口 DTO
  kb-infrastructure  MySQL、MinIO、Milvus、解析器、模型调用等基础设施实现
  kb-bootstrap       Spring Boot 启动模块
```

### 前端

- React 19
- TypeScript
- Vite
- Ant Design 5
- Axios
- lucide-react

前端目录：

```text
frontend/
  src/api          API 请求封装
  src/components   页面组件
  src/pages        页面入口
  src/types        前端类型定义
  src/styles.css   全局样式
```

### 基础设施

本地开发通过 Docker Compose 启动以下服务：

- MySQL：关系型数据存储
- MinIO：文件与 chunk 内容对象存储
- etcd：Milvus 依赖
- Milvus：向量数据库
- Attu：Milvus Web 控制台

## 目录结构

```text
.
├── backend                 后端多模块 Maven 工程
├── frontend                前端 React 工程
├── docs                    设计文档、计划文档、RAG 示例文档
├── docker-compose.yml      本地基础设施编排
├── .gitignore
└── README.md
```

## 本地环境准备

需要提前安装：

- JDK 21
- Maven
- Node.js
- Docker Desktop

本项目的 MySQL、MinIO、Milvus、Attu 都通过 Docker 启动，不需要单独安装这些服务。

## 启动基础设施

在项目根目录执行：

```bash
docker compose up -d
```

查看容器状态：

```bash
docker ps
```

正常情况下会看到：

- `agent-mysql`
- `agent-minio`
- `agent-etcd`
- `agent-milvus`
- `agent-attu`

停止服务：

```bash
docker compose down
```

如果只想停止但保留容器和数据卷，也可以使用：

```bash
docker compose stop
```

再次启动：

```bash
docker compose start
```

## 服务访问地址

### 后端

```text
http://localhost:8080
```

### 前端

```text
http://localhost:5173
```

### MinIO 控制台

```text
http://localhost:9001
```

账号：

```text
minioadmin
```

密码：

```text
minioadmin123
```

### Attu / Milvus 控制台

```text
http://localhost:8000
```

连接地址：

```text
milvus:19530
```

如果从宿主机工具连接 Milvus，使用：

```text
localhost:19530
```

### MySQL

连接信息：

```text
Host: localhost
Port: 3306
Database: enterprise_kb
Username: enterprise_kb
Password: enterprise_kb123456
```

Root 密码：

```text
root123456
```

DBeaver 如果遇到 `Public Key Retrieval is not allowed`，可以在连接参数中增加：

```text
allowPublicKeyRetrieval=true
useSSL=false
```

## 后端启动

先确保 Docker 服务已启动：

```bash
docker compose up -d
```

编译后端：

```bash
cd backend
mvn clean install -DskipTests
```

启动 Spring Boot：

```bash
cd backend
mvn -pl kb-bootstrap spring-boot:run
```

如果你本地 Maven 仓库使用项目约定路径，可以执行：

```bash
cd backend
mvn -Dmaven.repo.local=/Users/tangjie/mvnRepo -pl kb-bootstrap spring-boot:run
```

## 前端启动

进入前端目录：

```bash
cd frontend
```

首次启动前安装依赖：

```bash
npm install
```

启动开发服务：

```bash
npm run start
```

构建前端：

```bash
npm run build
```

## 大模型与 Embedding 配置

项目中涉及三类模型能力：

- Chunk enrichment：给 chunk 生成摘要和模拟问题。
- Query/RAG：路由判断、查询改写、HyDE、多 Query、最终回答。
- Embedding：生成向量。
- Rerank：对召回内容做精排。

默认配置在：

```text
backend/kb-bootstrap/src/main/resources/application.yml
```

本地密钥建议放在：

```text
backend/kb-bootstrap/src/main/resources/application-test.yml
```

该文件已加入 `.gitignore`，不要提交真实密钥。

示例结构：

```yaml
rag:
  enrichment:
    api-key: "your-key"
  embedding:
    api-key: "your-key"
    base-url: "https://your-workspace.cn-beijing.maas.aliyuncs.com/api/v1"
  query:
    api-key: "your-key"
  retrieval:
    rerank-api-key: "your-key"
```

也可以根据后续需要改成环境变量注入。

## 数据初始化说明

MySQL 初始化脚本挂载在 Docker Compose 中：

```text
backend/kb-infrastructure/src/main/resources/db/schema.sql
```

首次创建 MySQL 数据卷时会自动执行。

如果执行过：

```bash
docker compose down
```

只会删除容器，不会删除数据卷，因此不会重复初始化表。

如果执行：

```bash
docker compose down -v
```

会删除数据卷，下次启动时 MySQL 会重新初始化。

## 核心流程

### 文件索引构建流程

```text
上传文件
  ↓
保存原文件到 MinIO
  ↓
保存文件元数据到 MySQL
  ↓
创建索引任务
  ↓
定时任务扫描 pending
  ↓
解析文档
  ↓
清洗文本
  ↓
chunk 切分
  ↓
chunk 原文写 MinIO
  ↓
chunk 元数据写 MySQL
  ↓
生成摘要和模拟问题
  ↓
生成 embedding
  ↓
写入 Milvus
```

### RAG 对话流程

```text
用户输入问题
  ↓
保存用户消息
  ↓
加载最近会话上下文
  ↓
加载上一轮可复用 RAG 引用
  ↓
Router 判断是否查询知识库
  ↓
执行 Query Rewrite / HyDE / Multi Query / BM25 / Dense Retrieval
  ↓
RRF 融合
  ↓
Rerank
  ↓
构造上下文
  ↓
流式生成回答
  ↓
保存助手消息、检索记录、引用记录
```

## 设计特点

### 1. 面向后续 Agent 扩展

当前版本不直接做 Agent，但后端结构预留了扩展口：

- RAG Router 可继续扩展为工具选择器。
- 会话上下文可扩展为记忆系统。
- 检索链路可扩展为 Agent Skill。
- RAG 查询结果可作为 Agent 执行上下文。

### 2. 索引构建 Pipeline 化

索引构建已拆成多个步骤：

- 解析
- 清洗
- Chunk
- Enrichment
- Embedding

后续如果要加 PDF、OCR、表格解析、图片理解、Graph RAG，可以在索引 Pipeline 中继续加步骤。

### 3. 检索任务 Executor 化

检索增强能力按任务拆分：

- 原始 Query 检索
- Query Rewrite 检索
- HyDE 检索
- Multi Query 检索
- BM25 检索

各任务可以并发执行，最终统一融合和排序。

### 4. 对话编排可扩展

RAG 对话已按 Action Handler 拆分：

- 不查询知识库
- 查询知识库
- 复用上一轮上下文
- 复用并补充检索

后续加上下文压缩、长期记忆、工具调用时，可以继续扩展。

## 常用排查命令

查看 Docker 容器：

```bash
docker ps
```

查看 Milvus 健康状态：

```bash
curl http://localhost:9091/healthz
```

查看 Milvus 日志：

```bash
docker logs --tail=200 agent-milvus
```

查看 MinIO 日志：

```bash
docker logs --tail=200 agent-minio
```

查看 MySQL 日志：

```bash
docker logs --tail=200 agent-mysql
```

后端编译：

```bash
cd backend
mvn -DskipTests compile
```

前端类型检查：

```bash
cd frontend
npm run build
```

## 当前边界

当前项目仍是 Demo 阶段，以下能力还可以继续增强：

- PDF 解析。
- OCR。
- 表格结构化解析。
- 图片内容理解。
- RAG 自动评估。
- 上下文压缩。
- 长期记忆。
- Agent 工具调用。
- 权限与登录。
- 生产级部署脚本。

## 推荐体验路径

1. 启动 Docker 服务。
2. 启动后端。
3. 启动前端。
4. 创建一个知识库。
5. 上传 Markdown、DOCX 或 TXT 文件。
6. 等待文件状态变为 `READY`。
7. 打开 RAG 对话。
8. 提问与文档相关的问题。
9. 查看回答和引用来源。

这条路径可以验证从文件上传到 RAG 问答的完整闭环。

