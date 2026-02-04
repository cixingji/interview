# AI Interview Platform - 快速启动指南

## 项目概述

这是一个基于大语言模型的智能面试辅助平台，集成了简历分析、模拟面试和知识库管理功能。后端采用 Spring Boot 4.0 + Java 21 + Spring AI 2.0，前端使用 React 18 + TypeScript + Vite。

## 环境要求

| 依赖 | 版本 | 必需 | 说明 |
|------|------|------|------|
| JDK | 21+ | ✅ | Java 开发环境 |
| Node.js | 18+ | ✅ | 前端运行环境 |
| PostgreSQL | 14+ | ✅ | 数据库，需要安装 pgvector 扩展 |
| Redis | 6+ | ✅ | 缓存和消息队列 |
| pnpm | 最新版 | ✅ | 前端包管理器 |
| RustFS/MinIO | - | ✅ | S3 兼容的对象存储 |

## 启动步骤

### 1. 环境准备

#### 1.1 配置数据库

```sql
-- 创建数据库
CREATE DATABASE interview_guide;

-- 连接数据库并启用 pgvector 扩展
\c interview_guide;
CREATE EXTENSION IF NOT EXISTS vector;
```

#### 1.2 配置 Redis

确保 Redis 服务已启动，默认端口 6379。

#### 1.3 配置对象存储

可以使用 RustFS 或 MinIO，确保 S3 兼容存储服务已启动。

### 2. 后端启动步骤

#### 2.1 进入后端目录

```bash
cd app
```

**说明**：进入后端应用目录，包含所有 Java 源代码和配置文件。

#### 2.2 配置应用参数

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/interview_guide
    username: your_db_username      # 修改为你的数据库用户名
    password: your_db_password    # 修改为你的数据库密码

  ai:
    openai:
      api-key: your_api_key        # 替换为你的阿里云 DashScope API Key

app:
  storage:
    endpoint: http://localhost:9000    # S3 兼容存储地址
    access-key: your_access_key        # 对象存储 Access Key
    secret-key: your_secret_key        # 对象存储 Secret Key
```

**说明**：配置数据库连接、AI API Key 和对象存储信息。

#### 2.3 启动后端服务

```bash
# Windows
..\gradlew.bat bootRun

# Linux/Mac
../gradlew bootRun
```

**说明**：使用 Gradle Wrapper 启动 Spring Boot 应用，自动下载依赖并运行。

#### 2.4 验证后端启动

后端服务启动成功后：
- 访问：http://localhost:8080
- API 文档：http://localhost:8080/swagger-ui.html（如果有配置）

### 3. 前端启动步骤

#### 3.1 进入前端目录

```bash
cd frontend
```

**说明**：进入前端应用目录，包含 React + TypeScript 代码。

#### 3.2 安装依赖

```bash
pnpm install
```

**说明**：使用 pnpm 安装前端依赖包，包括 React、Vite、Tailwind CSS 等。

#### 3.3 启动开发服务器

```bash
pnpm dev
```

**说明**：启动 Vite 开发服务器，支持热重载和代理配置。

#### 3.4 验证前端启动

前端服务启动成功后：
- 访问：http://localhost:5173
- 会自动代理 API 请求到 http://localhost:8080

### 4. 完整启动流程（一键脚本）

创建启动脚本 `start-all.sh`（Linux/Mac）或 `start-all.bat`（Windows）：

#### Linux/Mac 版本

```bash
#!/bin/bash
echo "🚀 启动 AI Interview Platform..."

# 启动后端
echo "📦 启动后端服务..."
cd app
../gradlew bootRun &
BACKEND_PID=$!

# 等待后端启动
sleep 15

# 启动前端
echo "🎨 启动前端服务..."
cd ../frontend
pnpm dev &
FRONTEND_PID=$!

echo "✅ 启动完成！"
echo "📋 后端地址: http://localhost:8080"
echo "🌐 前端地址: http://localhost:5173"
echo ""
echo "按 Ctrl+C 停止所有服务"

# 等待用户中断
wait
```

#### Windows 版本

```batch
@echo off
echo 🚀 启动 AI Interview Platform...

:: 启动后端
echo 📦 启动后端服务...
cd app
start cmd /k "..\gradlew.bat bootRun"

:: 等待后端启动
timeout /t 15 /nobreak > nul

:: 启动前端
echo 🎨 启动前端服务...
cd ..\frontend
start cmd /k "pnpm dev"

echo ✅ 启动完成！
echo 📋 后端地址: http://localhost:8080
echo 🌐 前端地址: http://localhost:5173
echo.
echo 关闭这两个命令窗口即可停止服务
pause
```

### 5. 常用命令汇总

| 操作 | 后端命令 | 前端命令 |
|------|----------|----------|
| 安装依赖 | `../gradlew build` | `pnpm install` |
| 开发模式 | `../gradlew bootRun` | `pnpm dev` |
| 生产构建 | `../gradlew build` | `pnpm build` |
| 运行测试 | `../gradlew test` | - |
| 清理构建 | `../gradlew clean` | `pnpm clean` |

### 6. 配置检查清单

启动前请确认以下配置：

- [ ] PostgreSQL 服务已启动，数据库已创建
- [ ] pgvector 扩展已安装
- [ ] Redis 服务已启动
- [ ] S3 兼容存储服务已启动
- [ ] 数据库连接配置正确
- [ ] AI API Key 已配置
- [ ] 对象存储配置正确
- [ ] 端口未被占用（8080, 5173, 6379, 9000）

### 7. 故障排除

#### 7.1 端口冲突

```bash
# 检查端口占用
netstat -ano | findstr :8080    # Windows
lsof -i :8080                     # Linux/Mac
```

#### 7.2 数据库连接失败

- 检查 PostgreSQL 服务状态
- 确认数据库名称、用户名、密码正确
- 检查防火墙设置

#### 7.3 Redis 连接失败

- 检查 Redis 服务状态
- 确认 Redis 端口和配置

#### 7.4 构建失败

```bash
# 清理并重新构建
../gradlew clean build
cd frontend && pnpm install
```

### 8. 访问应用

启动成功后，打开浏览器访问：
- **前端界面**：http://localhost:5173
- **后端 API**：http://localhost:8080

### 9. 默认功能测试

1. **简历上传**：点击上传简历，支持 PDF、DOCX、TXT 格式
2. **模拟面试**：基于上传的简历生成面试问题
3. **知识库管理**：上传文档构建知识库
4. **智能问答**：基于知识库进行问答

### 10. 停止服务

- **后端**：在启动窗口按 `Ctrl+C`
- **前端**：在启动窗口按 `Ctrl+C`
- **一键脚本**：关闭对应的命令窗口

---

## 技术支持

如有问题，请检查：
1. 所有依赖服务是否正常运行
2. 配置文件是否正确
3. 查看控制台日志获取详细错误信息
4. 确保所有端口未被其他应用占用