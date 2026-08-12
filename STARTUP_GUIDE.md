# Interview Guide 启动、数据库与部署指南

本文档对应 `cixingji/interview` 仓库的 `codex/react-training` 分支，说明如何启动
Spring Boot 后端、React 前端及 PostgreSQL、Redis、S3 对象存储，并说明数据库 SQL
初始化、Flyway 迁移、ReAct 弱项训练前置条件和常见故障处理。

> 本地开发推荐使用“Docker 只启动依赖，后端和前端在宿主机运行”的方式。这样调试、
> 查看日志和修改代码最直接。

## 1. 启动方式选择

| 方式 | 适用场景 | 启动内容 | 访问地址 |
| --- | --- | --- | --- |
| 本地开发 | 日常开发、断点调试 | Compose 启动依赖，Gradle 启动后端，pnpm 启动前端 | `http://localhost:5173` |
| 完整 Docker | 快速体验、联调 | Compose 构建并启动全部服务 | `http://localhost` |
| JAR 部署 | 服务器或传统部署 | 外部依赖 + 可执行 JAR + 单独部署前端 | 由部署环境决定 |

### 最短启动顺序

第一次本地开发可以直接按下面三组命令执行。

终端一，仓库根目录：

```powershell
Copy-Item .env.example .env
# 编辑 .env，至少替换 AI_BAILIAN_API_KEY 和 APP_AI_CONFIG_ENCRYPTION_KEY
docker compose --env-file .env -f docker-compose.dev.yml up -d
.\gradlew.bat :app:bootRun
```

后端启动时会自动执行 Flyway SQL，不需要手工导入业务表。

终端二：

```powershell
Set-Location frontend
corepack enable
pnpm install --frozen-lockfile
pnpm run dev
```

浏览器打开：

```text
前端:    http://localhost:5173
Swagger: http://localhost:8080/swagger-ui.html
健康检查: http://localhost:8080/actuator/health
```

## 2. 环境要求

### 2.1 必需软件

| 软件 | 版本要求 | 说明 |
| --- | --- | --- |
| Git | 较新版本 | 拉取和更新代码 |
| JDK | **25** | Gradle Toolchain 和编译目标均为 Java 25 |
| Docker Desktop / Docker Engine | 支持 Compose V2 | 推荐用于 PostgreSQL、Redis、RustFS/MinIO |
| Node.js | 18+，推荐 20+ | React 前端构建 |
| pnpm | 10.26.x | `package.json` 指定 `pnpm@10.26.2` |

项目使用 Gradle Wrapper，不需要单独安装 Gradle。

### 2.2 Windows 检查命令

```powershell
git --version
java -version
docker version
docker compose version
node --version
pnpm --version
```

如果 `java -version` 不是 Java 25，应先设置 `JAVA_HOME`，再重新打开终端：

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

如果没有 pnpm，可以使用 Corepack：

```powershell
corepack enable
corepack prepare pnpm@10.26.2 --activate
```

## 3. 获取正确代码

首次克隆二开分支：

```powershell
git clone -b codex/react-training https://github.com/cixingji/interview.git
Set-Location interview
```

已有仓库时：

```powershell
git fetch origin
git switch codex/react-training
git pull --ff-only origin codex/react-training
```

确认当前分支：

```powershell
git branch --show-current
git log -1 --oneline
```

预期分支为 `codex/react-training`。

## 4. 配置 `.env`

### 4.1 创建配置文件

在仓库根目录执行：

```powershell
Copy-Item .env.example .env
```

Linux/macOS：

```bash
cp .env.example .env
```

`.env` 已被 Git 忽略。不得提交真实 API Key、数据库密码或加密密钥。

### 4.2 最低可用配置

编辑根目录 `.env`，至少确认以下内容：

```dotenv
# DashScope 文本模型、Embedding、ASR 和 TTS
AI_BAILIAN_API_KEY=替换为真实的百炼_API_Key
AI_MODEL=qwen3.5-flash

# Provider API Key 的落盘加密密钥。部署后必须保持不变。
APP_AI_CONFIG_ENCRYPTION_KEY=替换为随机长字符串

# PostgreSQL。复制 .env.example 后，Compose 和 bootRun 会使用同一密码。
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=interview_guide
POSTGRES_USER=postgres
POSTGRES_PASSWORD=password

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# 本地开发 Compose 使用 RustFS
APP_STORAGE_ENDPOINT=http://localhost:9000
APP_STORAGE_ACCESS_KEY=rustfsadmin
APP_STORAGE_SECRET_KEY=rustfsadmin
APP_STORAGE_BUCKET=interview-guide
```

可以在 PowerShell 中生成 32 字节随机加密密钥：

```powershell
[Convert]::ToBase64String(
  [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
)
```

### 4.3 `.env` 的读取规则

- `.\gradlew.bat :app:bootRun` 会由 `app/build.gradle` 自动读取**仓库根目录** `.env`。
- `docker compose` 默认读取当前项目目录的 `.env`，也可以显式传入 `--env-file .env`。
- `java -jar app.jar` **不会自动读取 `.env`**。JAR 部署必须由 Shell、systemd、容器编排
  或密钥管理服务显式注入环境变量。
- Vite 只读取 `frontend/.env*` 中的前端变量。不要把后端密钥放进前端环境文件。

### 4.4 可选 ReAct 配置

不填写时会使用安全默认值：

```dotenv
APP_TRAINING_MAX_QUESTIONS=10
APP_TRAINING_MAX_CONSECUTIVE_PER_TOPIC=3
APP_TRAINING_MAX_FOLLOW_UPS=2
APP_TRAINING_MINIMUM_TOPIC_COUNT=2
APP_TRAINING_MINIMUM_EVIDENCE_PER_TOPIC=2
APP_TRAINING_MAX_DIAGNOSTIC_TOPICS=5
APP_TRAINING_MAX_EVIDENCE_PER_TOPIC=5
APP_TRAINING_MAX_SOURCE_ANSWERS=200
APP_TRAINING_QUEUED_STALE_DURATION=2m
APP_TRAINING_PROCESSING_STALE_DURATION=20m
APP_TRAINING_RECOVERY_INTERVAL=1m
APP_TRAINING_RECOVERY_BATCH_SIZE=50
```

配置约束：

- `MAX_EVIDENCE_PER_TOPIC` 不能小于 `MINIMUM_EVIDENCE_PER_TOPIC`。
- `MAX_SOURCE_ANSWERS` 不能小于 `MINIMUM_EVIDENCE_PER_TOPIC`。
- `PROCESSING_STALE_DURATION` 必须大于 `QUEUED_STALE_DURATION`。
- 关键限制会在创建训练时写入会话；修改环境变量不会改变已经开始的训练。

## 5. 推荐方式：本地开发启动

所有命令默认从仓库根目录执行。

### 5.1 启动 PostgreSQL、Redis、RustFS

```powershell
docker compose --env-file .env -f docker-compose.dev.yml up -d
```

查看状态：

```powershell
docker compose -f docker-compose.dev.yml ps
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

预期端口：

| 服务 | 容器 | 宿主机端口 | 用途 |
| --- | --- | ---: | --- |
| PostgreSQL + pgvector | `interview-postgres` | 5432 | 业务库和向量库 |
| Redis | `interview-redis` | 6379 | 缓存、限流、Redis Stream 异步任务 |
| RustFS S3 API | `interview-rustfs` | 9000 | 文件上传下载 |
| RustFS 控制台 | `interview-rustfs` | 9001 | Bucket 管理 |

查看依赖日志：

```powershell
docker compose -f docker-compose.dev.yml logs -f postgres redis rustfs
```

RustFS 控制台为 `http://localhost:9001`，默认账号和密码都是 `rustfsadmin`。应用配置
`APP_STORAGE_AUTO_CREATE_BUCKET=true` 时会自动创建 `interview-guide` Bucket；若自动创建
失败，也可以在控制台手工创建同名 Bucket。

### 5.2 启动后端

打开新的 PowerShell：

```powershell
Set-Location D:\你的路径\interview
.\gradlew.bat :app:bootRun
```

Linux/macOS：

```bash
./gradlew :app:bootRun
```

后端启动时会依次完成：

1. 连接 PostgreSQL。
2. 通过 Flyway 自动执行尚未应用的 SQL 迁移。
3. Hibernate 使用 `ddl-auto: validate` 校验表结构。
4. 连接 Redis，并初始化 Stream 消费组和异步消费者。
5. 初始化 S3 客户端并检查/创建 Bucket。
6. 加载 LLM Provider、Prompt 和 Skill 资源。

后端默认端口为 `8080`。

### 5.3 启动 React 前端

打开第三个 PowerShell：

```powershell
Set-Location D:\你的路径\interview\frontend
pnpm install --frozen-lockfile
pnpm run dev
```

前端默认地址为 `http://localhost:5173`。Vite 会把 `/api` 代理到
`http://localhost:8080`，本地开发一般不需要额外配置 CORS。

后端不在 8080 时，在 `frontend/.env.local` 中设置：

```dotenv
VITE_API_PROXY_TARGET=http://localhost:新的后端端口
```

然后重启 Vite。

### 5.4 启动成功检查

| 检查项 | 地址或命令 |
| --- | --- |
| 前端 | `http://localhost:5173` |
| 弱项训练页 | `http://localhost:5173/training` |
| Swagger | `http://localhost:8080/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` |
| Actuator 健康检查 | `http://localhost:8080/actuator/health` |
| 简历模块健康检查 | `http://localhost:8080/api/resumes/health` |
| RustFS 控制台 | `http://localhost:9001` |

PowerShell API 检查：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/api/resumes/health
```

## 6. SQL 与 Flyway 数据库迁移

### 6.1 正常情况不需要手工导入业务 SQL

数据库结构由 Flyway 管理：

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
  jpa:
    hibernate:
      ddl-auto: validate
```

应用启动时，Flyway 会按版本顺序执行 `app/src/main/resources/db/migration/V*.sql`，
并把结果写入 `flyway_schema_history`。

**不要使用 `psql -f` 逐个执行这些迁移文件。** 手工执行不会自动建立正确的 Flyway
历史，后续启动可能发生重复建表、版本状态不一致或 checksum 错误。

### 6.2 首次创建数据库的过程

使用 `docker-compose.dev.yml` 时：

1. PostgreSQL 容器首次创建数据卷。
2. `docker/postgres/init.sql` 安装 `vector` 扩展。
3. 后端首次启动。
4. Flyway 执行 `V1__init_schema.sql` 和全部后续迁移。
5. Hibernate 校验 Entity 与数据库结构。

当前迁移顺序：

| 迁移 | 作用 |
| --- | --- |
| `V1__init_schema.sql` | 初始化原项目业务表、扩展、向量表和索引 |
| `V20260722__add_interview_category_to_interview_sessions.sql` | 面试分类字段 |
| `V20260723__ensure_pgvector_store.sql` | 确保扩展、1024 维向量表和 HNSW 索引存在 |
| `V20260724__add_question_gen_status.sql` | 知识库题目生成状态 |
| `V20260729__create_training_schema.sql` | ReAct 训练会话与弱项诊断主题 |
| `V20260730__create_training_turn_tasks.sql` | 训练轮次与可靠异步任务 |
| `V20260731__create_training_summaries.sql` | 训练总结及 `SUMMARY` 任务类型 |

### 6.3 进入 PostgreSQL

```powershell
docker exec -it interview-postgres psql -U postgres -d interview_guide
```

如果修改了 `POSTGRES_USER` 或 `POSTGRES_DB`，替换命令中的用户名和数据库名。

常用 `psql` 命令：

```sql
\conninfo
\dt
\dx
\d training_sessions
\d training_tasks
\q
```

### 6.4 验证扩展和迁移

```sql
SELECT extname, extversion
FROM pg_extension
WHERE extname IN ('vector', 'hstore', 'uuid-ossp')
ORDER BY extname;

SELECT installed_rank, version, description, type, success, installed_on
FROM flyway_schema_history
ORDER BY installed_rank;

SELECT tablename
FROM pg_tables
WHERE schemaname = 'public'
  AND tablename IN (
    'training_sessions',
    'training_topics',
    'training_turns',
    'training_tasks',
    'training_summaries',
    'vector_store'
  )
ORDER BY tablename;
```

ReAct 所需表应全部存在：

```text
training_sessions
training_summaries
training_tasks
training_topics
training_turns
```

检查向量维度：

```sql
SELECT format_type(a.atttypid, a.atttypmod) AS embedding_type
FROM pg_attribute a
JOIN pg_class c ON c.oid = a.attrelid
WHERE c.relname = 'vector_store'
  AND a.attname = 'embedding';
```

预期为 `vector(1024)`。不能只修改 `APP_AI_EMBEDDING_DIMENSIONS` 就改变已有列维度；
修改维度需要新的数据库迁移，并重新生成现有向量。

### 6.5 验证 ReAct 异步任务状态

```sql
SELECT task_id, task_type, status, attempt_count, retry_count,
       safe_error_message, created_at, updated_at, completed_at
FROM training_tasks
ORDER BY id DESC
LIMIT 20;

SELECT training_id, status, question_count, covered_topic_count,
       current_topic_key, created_at, updated_at
FROM training_sessions
ORDER BY id DESC
LIMIT 20;

SELECT s.training_id, t.turn_index, t.action, t.topic_key, t.status,
       t.answered_at, t.completed_at
FROM training_turns t
JOIN training_sessions s ON s.id = t.training_session_id
ORDER BY t.id DESC
LIMIT 20;
```

正常任务状态大致为：

```text
QUEUED -> ANALYZING -> RETRIEVING -> DECIDING -> GENERATING -> COMPLETED
```

不调用工具时可以跳过 `RETRIEVING`。最终失败状态为 `FAILED`，前端会显示允许用户重试
的安全提示。

### 6.6 已有数据库升级

升级前：

1. 备份数据库。
2. 停止正在写入的旧版本应用。
3. 拉取新代码。
4. 不修改已经执行过的迁移文件。
5. 启动新版本，让 Flyway 自动执行新增迁移。
6. 检查 `flyway_schema_history.success` 和应用启动日志。

如果出现 Flyway checksum mismatch，不要删除 `flyway_schema_history`，也不要直接修改
数据库迁移记录。应先确认是否有人改动了已经发布的 `V*.sql`，再决定恢复原文件或执行
经过审核的 Flyway repair。

### 6.7 开发环境重建空库

下面的命令会永久删除 PostgreSQL、Redis 和 RustFS 的本地数据卷：

```powershell
docker compose -f docker-compose.dev.yml down -v
docker compose --env-file .env -f docker-compose.dev.yml up -d
```

仅在确认本地数据可以丢弃时使用。生产环境禁止这样操作。

### 6.8 备份与恢复

备份：

```powershell
docker exec interview-postgres pg_dump `
  -U postgres -d interview_guide -Fc `
  -f /tmp/interview_guide.dump

docker cp interview-postgres:/tmp/interview_guide.dump .\interview_guide.dump
```

恢复前应停止应用写入，并恢复到空数据库或经过确认的目标库：

```powershell
docker cp .\interview_guide.dump interview-postgres:/tmp/interview_guide.dump

docker exec -it interview-postgres pg_restore `
  -U postgres -d interview_guide `
  --clean --if-exists /tmp/interview_guide.dump
```

`--clean` 会删除备份中已有的数据库对象，执行前必须再次确认目标数据库。

## 7. ReAct 弱项训练启动前置条件

仅仅启动服务还不能立即生成训练。创建训练会话需要历史诊断数据：

1. 至少完成一场文字或知识库面试。
2. 面试必须完成评估，状态为 `EVALUATED`。
3. 同一个问题分类默认至少有 2 条有效回答。
4. 回答必须包含题目、用户答案和 0 到 100 的评分。
5. 创建训练时选择的 Skill 和可选简历范围必须能匹配这些历史回答。

没有足够数据时，接口会返回 `TRAINING_HISTORY_INSUFFICIENT`，这不是 Redis 或 ReAct
循环故障。

ReAct 训练还依赖：

- 可用的聊天模型 Provider。
- PostgreSQL 中的训练表。
- Redis Stream 生产者、消费者和消费组。
- 后端定时恢复调度器。

ReAct 本身不依赖 pgvector 检索，但项目启动和知识库模块仍要求 PostgreSQL 已安装
`vector` 扩展并具有 1024 维 `vector_store`。

## 8. 完整 Docker 启动

完整 Compose 会构建并启动 PostgreSQL、Redis、MinIO、后端和 Nginx 前端：

```powershell
docker compose --env-file .env up -d --build
docker compose ps
docker compose logs -f app frontend
```

访问：

- 前端：`http://localhost`
- 后端：`http://localhost:8080`
- Swagger：`http://localhost:8080/swagger-ui.html`
- MinIO 控制台：`http://localhost:9001`

完整 Compose 使用 MinIO：

```text
用户名: minioadmin
密码:   minioadmin
Bucket: interview-guide
```

`createbuckets` 初始化容器会自动创建 Bucket。它正常执行完后状态为 `Exited (0)`，
不是服务故障。

停止但保留数据：

```powershell
docker compose down
```

重新构建应用：

```powershell
docker compose up -d --build app frontend
```

### 完整 Docker 的生产注意事项

当前 `docker-compose.yml` 主要用于本机体验，正式部署前至少需要：

- 替换 PostgreSQL、MinIO 和所有 API Key。
- 使用 Secret 管理，不把密钥直接写进 Compose。
- 将 Provider 加密密钥持久化并安全备份。
- 配置 HTTPS、域名和真实 CORS Origin。
- 为 PostgreSQL、Redis 和对象存储配置备份。
- 限制数据库、Redis、MinIO API 和后端 8080 端口的公网暴露。
- 为 Nginx 配置 `/ws/voice-interview/` WebSocket Upgrade 代理。
- 修正语音会话当前返回的 `ws://localhost:8080/...` 地址，使其使用部署域名和 `wss://`。

最后两项只影响远程部署的语音面试。本机通过映射的 `localhost:8080` 访问时通常可用。

## 9. JAR 启动

构建：

```powershell
.\gradlew.bat :app:bootJar
```

产物位于：

```text
app/build/libs/
```

直接运行 JAR 前必须设置环境变量：

```powershell
$env:AI_BAILIAN_API_KEY = "真实密钥"
$env:APP_AI_CONFIG_ENCRYPTION_KEY = "固定的随机加密密钥"
$env:POSTGRES_HOST = "localhost"
$env:POSTGRES_PORT = "5432"
$env:POSTGRES_DB = "interview_guide"
$env:POSTGRES_USER = "postgres"
$env:POSTGRES_PASSWORD = "password"
$env:REDIS_HOST = "localhost"
$env:REDIS_PORT = "6379"
$env:APP_STORAGE_ENDPOINT = "http://localhost:9000"
$env:APP_STORAGE_ACCESS_KEY = "rustfsadmin"
$env:APP_STORAGE_SECRET_KEY = "rustfsadmin"

java --enable-native-access=ALL-UNNAMED `
  -Dfile.encoding=UTF-8 `
  -jar app\build\libs\实际生成的文件名.jar
```

不要假设 `java -jar` 会读取根目录 `.env`。

## 10. 停止、重启和查看日志

停止本地后端或前端：在对应终端按 `Ctrl+C`。

停止开发依赖但保留数据：

```powershell
docker compose -f docker-compose.dev.yml down
```

重启单个依赖：

```powershell
docker compose -f docker-compose.dev.yml restart postgres
docker compose -f docker-compose.dev.yml restart redis
docker compose -f docker-compose.dev.yml restart rustfs
```

查看最近日志：

```powershell
docker logs --tail 200 interview-postgres
docker logs --tail 200 interview-redis
docker logs --tail 200 interview-rustfs
```

## 11. 常见启动故障

### 11.1 PostgreSQL 密码错误

表现：

```text
password authentication failed for user "postgres"
```

检查 `.env` 的 `POSTGRES_PASSWORD` 是否同时被 Compose 和 `bootRun` 使用。PostgreSQL
密码只在数据卷首次初始化时生效；修改 `.env` 不会自动修改旧数据卷里的用户密码。

开发数据可以丢弃时可以执行 `down -v` 重建；需要保留数据时应使用 SQL 修改用户密码。

### 11.2 连接 `localhost:5432` 被拒绝

```powershell
docker ps --format "table {{.Names}}\t{{.Ports}}"
```

应看到 `0.0.0.0:5432->5432/tcp`。只有 `5432/tcp` 表示端口没有发布到宿主机。

```powershell
docker compose -f docker-compose.dev.yml up -d --force-recreate postgres redis
```

### 11.3 `vector` 类型或扩展不存在

先检查：

```sql
SELECT extname FROM pg_extension WHERE extname = 'vector';
```

必须使用包含 pgvector 的 PostgreSQL，例如 Compose 中的 `pgvector/pgvector:pg16`。
标准 PostgreSQL 镜像没有预装 pgvector 扩展文件。

### 11.4 Flyway 校验失败

检查：

```sql
SELECT * FROM flyway_schema_history ORDER BY installed_rank;
```

常见原因：

- 修改了已执行的迁移文件。
- 手工执行迁移但没有正确的 Flyway 历史。
- 连接到了错误数据库。
- 老数据库结构与当前基线不一致。

不要通过删除历史表掩盖问题。

### 11.5 Hibernate schema validation 失败

`ddl-auto: validate` 不会自动补表。应先解决 Flyway 迁移失败，不能临时改成 `update`
作为生产方案。

### 11.6 Redis 连接失败或任务一直 `QUEUED`

```powershell
docker exec -it interview-redis redis-cli ping
docker logs --tail 200 interview-redis
```

预期返回 `PONG`。同时检查后端日志中的 Stream 消费组、消费者和任务恢复调度信息。

数据库是训练任务的事实来源；不要通过直接改 Redis 消息来强行完成任务。

### 11.7 RustFS/MinIO 返回 403

确认：

- Endpoint 使用 S3 API 端口 `9000`，不是控制台端口 `9001`。
- Access Key 和 Secret Key 与容器一致。
- Bucket 名为 `interview-guide`。
- 本地宿主机使用 `http://localhost:9000`。
- Docker 内后端使用服务名，例如 `http://minio:9000`。

### 11.8 缺少 Provider 加密密钥

`APP_AI_CONFIG_REQUIRE_ENCRYPTION_KEY` 默认为 `true`。必须提供
`APP_AI_CONFIG_ENCRYPTION_KEY`，并在后续部署中保持不变，否则已保存的 Provider API Key
可能无法解密。

### 11.9 LLM 请求失败

检查：

- `AI_BAILIAN_API_KEY` 是否真实有效。
- 当前网络是否允许访问 DashScope。
- `AI_MODEL` 是否为账号可调用的模型。
- 设置页选择的默认 Provider 是否已启用。
- ReAct 使用的是聊天模型；知识库向量化还需要支持 Embedding 的 Provider。

### 11.10 前端能打开但 API 404/502

本地开发检查：

```dotenv
VITE_API_PROXY_TARGET=http://localhost:8080
```

完整 Docker 检查 `app` 和 `frontend` 日志，并确认 Nginx 的 `/api/` 代理目标为
`http://app:8080`。

### 11.11 端口冲突

Windows：

```powershell
Get-NetTCPConnection -LocalPort 80,5173,5432,6379,8080,9000,9001 `
  -ErrorAction SilentlyContinue |
  Select-Object LocalPort, State, OwningProcess
```

修改端口后，还要同步数据库连接、Vite 代理、CORS、对象存储 Endpoint 或 Compose 映射。

## 12. 更新代码后的标准操作

本地开发：

```powershell
git switch codex/react-training
git pull --ff-only origin codex/react-training

docker compose --env-file .env -f docker-compose.dev.yml up -d
.\gradlew.bat :app:bootRun
```

另开终端：

```powershell
Set-Location frontend
pnpm install --frozen-lockfile
pnpm run dev
```

完整 Docker：

```powershell
git pull --ff-only origin codex/react-training
docker compose --env-file .env up -d --build
docker compose logs -f app frontend
```

每次升级后至少检查：

1. `flyway_schema_history` 最新迁移是否成功。
2. `/actuator/health` 是否为 `UP`。
3. Redis 是否为 `PONG`。
4. S3 Bucket 是否可访问。
5. Swagger 和前端是否正常。
6. 弱项训练页能否读取历史评估并创建训练会话。

## 13. 生产部署检查清单

- [ ] Java 25 运行环境或正确的后端容器镜像。
- [ ] PostgreSQL 14+ 且安装 pgvector，建议 PostgreSQL 16。
- [ ] 数据库和 `flyway_schema_history` 已备份。
- [ ] Redis 已启用持久化、认证和网络访问控制。
- [ ] S3 Bucket、凭证、权限和备份策略已配置。
- [ ] `AI_BAILIAN_API_KEY` 和其他 Provider Key 由 Secret 注入。
- [ ] `APP_AI_CONFIG_ENCRYPTION_KEY` 随机、固定并已安全备份。
- [ ] 数据库、Redis、S3 和后端管理端口未直接暴露公网。
- [ ] 域名、HTTPS、CORS 和 Nginx 代理正确。
- [ ] 语音面试使用 `wss://`，Nginx 支持 WebSocket Upgrade。
- [ ] Flyway 自动迁移成功，Hibernate schema validation 通过。
- [ ] Actuator 仅暴露必要端点，并由网关限制访问。
- [ ] 日志、指标、告警和数据库/对象存储备份可用。
- [ ] ReAct `QUEUED`、处理中和 `FAILED` 任务有监控或定期检查。
