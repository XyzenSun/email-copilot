# email-copilot

单用户、多邮箱账号聚合的 AI 邮件助手：IMAP 收信 → 流水线判定/翻译/摘要 → 对话 AI → 审批后 SMTP 发信。

## 技术栈

- 后端：Java 25 · Spring Boot 4.1 · MyBatis-Plus · PostgreSQL 18 · Flyway · Lucene
- 前端：Vue 3.5 · TypeScript · Vite

## 快速开始

### 后端

1. 准备 PostgreSQL 18 与一个空库（默认 `jdbc:postgresql://localhost:5432/email_agent`）。
2. 配置环境变量：
   - `DB_PASSWORD`：数据库口令
   - `EMAIL_COPILOT_MASTER_KEY`：凭据加密主密钥，`openssl rand -base64 32` 生成
3. `mvn spring-boot:run`（默认 8080 端口）。

### 前端

```bash
cd frontend
npm install
npm run dev
```

### Docker

官方镜像在 GHCR，启动前必须配置数据库口令与加密主密钥：

```bash
docker run -d --name email-copilot -p 8080:8080 \
  -e DB_PASSWORD='数据库口令' \
  -e EMAIL_COPILOT_MASTER_KEY="$(openssl rand -base64 32)" \
  -v ./data:/app/data \
  ghcr.io/xyzensun/email-copilot:latest
```

- `DB_PASSWORD`：PostgreSQL 口令。库地址/账号用容器参数覆盖，例如
  `--spring.datasource.url=jdbc:postgresql://db:5432/email_agent --spring.datasource.username=postgres`。
- `EMAIL_COPILOT_MASTER_KEY`：凭据加密主密钥，`openssl rand -base64 32` 生成。**首次启动前设置并妥善保存**，
  更换后既有凭据将无法解密。
- `JAVA_OPTS`：JVM 启动参数，默认 `-Xmx256m`。单机空闲进程约 330MB 内存，实测 256m 堆可完整启动且无 OOM；
  重度使用（大量 AI 会话/超大邮件/索引重建）可调大，如 `-e JAVA_OPTS="-Xmx512m"`。
- **数据目录权限（Lucene 索引）**：`/app/data` 以宿主绑定挂载 `./data`；容器内进程是 UID 10001（非 root），
  首次部署必须先让宿主目录可写：
  ```bash
  mkdir -p ./data && sudo chown -R 10001:10001 ./data
  ```
  跳过授权会报 `AccessDeniedException: /app/data/lucene`，表现为搜索不可用（收信与数据库不受影响）。
- `:latest` 之外可按发行版本拉取 `:0.2.0`（对应 git tag `v0.2.0` 去掉前导 `v`）。

或用 compose 一键起服务（默认对接远程 PostgreSQL，如 Aiven）：

```bash
cp .env.example .env   # 填入 DB_URL/DB_USERNAME/DB_PASSWORD
docker compose up -d
```

（compose 同样需要先把 `./data` 授权给 UID 10001，见上面的权限说明。）

镜像名由 `github.repository` 全小写拼出（GHCR 要求全小写），fork 后自动跟随 fork 的用户名/仓库名，无需改配置；
也可直接 `docker build -t email-copilot .` 构建本地自足镜像（多阶段构建已内嵌前端）。运行镜像基于 Alpine JRE（musl），
项目纯 Java 无原生依赖，体积比 Ubuntu 版约省 40MB。

## 发布

打 git tag（`v` 开头，如 `v0.2.0`）并推送后，在 GitHub 仓库 Actions 页手动运行 `Release` 工作流，
ref 选择该 tag。工作流会自动：

1. 编译后端可执行 jar（内嵌前端资源）与前端静态文件，附到该 tag 的 GitHub Release。
2. 构建 Docker 镜像并推送 GHCR（版本号取 tag，去掉前导 `v`）。

## 许可证

MIT
