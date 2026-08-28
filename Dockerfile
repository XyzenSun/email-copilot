# syntax=docker/dockerfile:1

# 多阶段构建：前端 → 后端 → 运行时，产出一个自带前端静态资源的单 jar 运行镜像。
# 与 GitHub Actions 发布工作流分工：workflow 负责编译 release 资产（jar + 前端 zip），
# 这里的 Dockerfile 独立自足，fork 用户可直接 docker build 无需先跑 workflow。

# ── 阶段 1：前端构建 ──────────────────────────────────────────────
# vite.config.ts 把 build.outDir 指向 ../src/main/resources/static（与后端同源部署），
# 因此产物天然落在后续 maven 阶段约定的路径，无需额外拷贝。
FROM node:24-alpine AS frontend
WORKDIR /build/frontend
# 先拷清单再装依赖：清单未变时可命中镜像缓存层，跳过 npm ci
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
# 产物写入 /build/src/main/resources/static
RUN npm run build

# ── 阶段 2：后端构建（把前端产物打进 static 后打成可执行 jar）──
FROM maven:3.9-eclipse-temurin-25 AS backend
WORKDIR /build
COPY pom.xml ./
COPY src ./src
COPY --from=frontend /build/src/main/resources/static ./src/main/resources/static/
# 仓库不提交测试代码；-DskipTests 只保证退回主代码编译打包，跳过 surefire 空跑
RUN mvn -B -DskipTests package

# ── 阶段 3：运行时 ───────────────────────────────────────────────
# Alpine JRE（musl）：项目纯 Java、无 JNI 原生依赖，比 Ubuntu 版约省 40MB。
# 堆默认 256m（实测完整启动 + 空闲无 OOM，进程 ~330MB）；余量不足时用
# -e JAVA_OPTS="-Xmx512m ..." 覆盖即可，Spring 参数追加在镜像名之后。
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=backend /build/target/*.jar ./app.jar
# Lucene 索引默认写工作目录下 data/lucene（SearchIndexProperties）。以非 root
# 用户运行；预先建好 /app/data 并把 /app 属主设为该用户——否则命名卷首次
# 挂载 /app/data 时由 root 初始化卷根目录，UID 10001 将无权写入索引
RUN addgroup -g 10001 -S appuser && adduser -S -u 10001 -G appuser -h /app -s /bin/false appuser \
    && mkdir -p /app/data && chown -R 10001:10001 /app
USER 10001
ENV JAVA_OPTS="-Xmx256m"
EXPOSE 8080
# JAVA_OPTS 经 sh 展开以支持用户覆盖；"--" 之后的参数透传给 java（docker run 追加在镜像名后）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]