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

## 许可证

MIT
