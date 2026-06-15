# 企业知识库文件管理

第一版只实现知识库空间和文件管理，不实现登录、RAG 解析、向量写入、检索或 Agent 应用。

## 技术栈

- 后端：Java、Spring Boot、Maven 多模块、MySQL、MyBatis-Plus、MinIO
- 前端：React、Vite、TypeScript、Ant Design
- 后续预留：AgentScope Java、Milvus、RAG pipeline

## 外部软件

当前项目需要以下外部软件，但安装和启动由用户执行：

- MySQL：保存知识库和文件元数据
- MinIO：保存原始文件
- Milvus：后续 RAG 阶段使用，第一版不强依赖

Codex 不会自动安装这些软件，除非用户明确要求。

## 前端启动

```text
cd frontend
npm install
npm run start
```

## 后端启动

后端模块创建完成后的预期命令：

```text
cd backend
mvn spring-boot:run -pl kb-bootstrap
```

## 需要用户执行的命令

前端依赖安装和构建由用户执行：

```text
cd frontend
npm install
npm run build
npm run start
```

后端需要本机安装 Maven 后执行：

```text
cd backend
mvn -q -DskipTests compile
```

MySQL 和 MinIO 安装、启动、账号配置由用户执行。Milvus 是后续 RAG 阶段依赖，第一版文件管理不强依赖。

## Docker 本地环境配置

`docker-compose.yml` 默认提供：

- MySQL：`localhost:3306`
- MinIO API：`http://localhost:9000`
- MinIO 控制台：`http://localhost:9001`
- Milvus：`localhost:19530`
- Attu：`http://localhost:8000`

后端默认配置：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/enterprise_kb?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: enterprise_kb
    password: enterprise_kb123456

app:
  storage:
    minio:
      endpoint: http://localhost:9000
      bucket: enterprise-kb-files
      access-key: minioadmin
      secret-key: minioadmin123
  vector:
    milvus:
      host: localhost
      port: 19530
      database: default
      collection-prefix: enterprise_kb
```

## 手工验证清单

1. 打开前端页面。
2. 创建一个知识库。
3. 在该知识库上传一个 `.txt` 文件。
4. 确认文件列表出现该文件，状态为 `UPLOADED`。
5. 再次上传同名文件，确认提示“同一知识库下已存在同名文件，请先删除旧文件。”
6. 查看文件详情，确认展示文件名、大小、状态、MinIO 信息。
7. 下载文件，确认内容正确。
8. 删除文件，确认列表移除。
9. 文件未清空时删除知识库，确认删除被拒绝。
10. 文件清空后删除知识库，确认删除成功。
