# ReAct 弱项训练模块

## 目标与前置条件

本模块以平台已有的模拟面试和评估结果为数据基础。它不会替代面试流程，而是在历史
回答已经完成评估后，按低分主题创建一份不可变诊断快照，再通过有界 ReAct 循环进行追问、
巩固、换题和切换主题。

创建训练至少需要一个主题拥有 `minimum-evidence-per-topic` 条有效评分样本，默认是两条。
可以用 `resumeId` 将历史证据限定到一份简历；不传时按 `skillId` 汇总通用面试历史。

## 业务流程

```text
创建会话 READY
  -> 启动首题任务
  -> IN_PROGRESS
  -> 用户回答
  -> Redis Stream 异步评估与决策
  -> FOLLOW_UP / REINFORCE / ASK_NEW_QUESTION / SWITCH_TOPIC
  -> 达到服务端结束条件
  -> SUMMARIZING
  -> 异步生成公开文字总结
  -> COMPLETED
```

HTTP 请求只负责校验、持久化和创建任务。LLM、只读工具和 Redis Stream 消费都在数据库
事务之外执行，数据库中的任务状态是轮询与恢复的事实来源。

## 有界 ReAct 约束

- 单个轮次任务最多执行 3 轮只读工具调用。
- 单个轮次任务最多调用聊天模型 4 次，包含最终结构化决策。
- 总结任务独立计算，最多执行 2 次结构化模型调用。
- 模型只能从服务端计算的 `allowedActions` 中选择动作。
- 训练题数、连续同主题题数、主问题追问数和最低主题覆盖数均由服务端状态控制。
- 模型输出未知动作、越界主题、非法分数或缺失文本时，由 `TrainingActionPolicy`
  确定性修正，不通过增加模型调用次数解决。

可用工具全部是当前训练会话范围内的只读查询：

1. `searchSkillReference`：读取当前 Skill 的参考资料。
2. `getHistoricalEvidence`：读取诊断快照中的有限历史证据。
3. `getPreviousTrainingTurns`：读取当前训练已完成的公开轮次。
4. `getRemainingWeakTopics`：读取尚未完成的诊断主题。

LLM 没有写数据库、写缓存、执行任意 SQL、访问任意会话 ID 或调用外部 URL 的工具。

## 异步任务与恢复

任务状态：

```text
QUEUED -> ANALYZING -> RETRIEVING -> DECIDING -> GENERATING -> COMPLETED
                                                            \-> FAILED
```

训练轮次和总结共用可靠任务状态机：

- 任务先写数据库，事务提交后才投递 Redis Stream。
- 消费者处理前重新校验会话和任务，实体已删除或任务已结束时直接 ACK。
- `deduplication_key` 防止首题、同一回答和总结被重复创建。
- `queued-stale-duration` 后仍未领取的任务会重新投递。
- `processing-stale-duration` 内没有进展的任务会重置为 `QUEUED` 后恢复。
- 前端只在服务端返回 `retryable=true` 时允许用户主动重试。

Redis Stream 只承担通知和分发。消息丢失不会改变数据库事实，恢复调度器会重新投递符合
条件的任务。

## API

所有接口统一返回 `Result<T>`，业务错误仍使用 HTTP 200 和非 200 业务码。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/training/sessions` | 创建诊断快照和 READY 会话 |
| GET | `/api/training/sessions/{trainingId}` | 查询会话及主题进度 |
| POST | `/api/training/sessions/{trainingId}/start` | 创建首题异步任务 |
| POST | `/api/training/sessions/{trainingId}/turns/{turnId}/answers` | 保存回答并创建决策任务 |
| GET | `/api/training/sessions/{trainingId}/tasks/{taskId}` | 轮询指定任务 |
| GET | `/api/training/sessions/{trainingId}/tasks/latest` | 页面刷新后恢复最新任务 |
| POST | `/api/training/sessions/{trainingId}/tasks/{taskId}/retry` | 重试允许恢复的失败任务 |
| GET | `/api/training/sessions/{trainingId}/turns` | 查询公开训练时间线 |
| GET | `/api/training/sessions/{trainingId}/summary` | 查询聚合训练总结 |

耗时 POST 只返回 `TrainingTaskDTO`。前端应轮询对应 taskId，不能根据固定等待时间推断任务
完成。`tasks/latest` 和 `summary` 在正常等待阶段都可能返回 `null`。

## 数据与隐私边界

公开 DTO 不包含以下字段：

- 单题 `internalScore`
- 诊断 `evidenceJson`
- 原始 `failureMessage`
- 模型原始响应和 Thought
- 工具调用参数、观察结果和数据库主键

单题内部评分只用于服务端动作策略和最终聚合。总结 Prompt 只接收已聚合总分、主题均分、
问题和公开反馈，LLM 不能提供或修改最终分数。

用户回答、历史回答和工具结果进入 Prompt 前都会经过长度限制和边界包装。系统 Prompt
明确将这些内容视为待分析数据，而不是可执行指令。

## 配置

配置前缀是 `app.training`，环境变量示例位于根目录 `.env.example`。

| 配置 | 默认值 | 说明 |
| --- | ---: | --- |
| `max-questions` | 10 | 单场最大题数 |
| `max-consecutive-questions-per-topic` | 3 | 同主题连续题数上限 |
| `max-follow-ups-per-main-question` | 2 | 单个主问题追问上限 |
| `minimum-topic-count` | 2 | 允许提前结束前最低覆盖主题数 |
| `minimum-evidence-per-topic` | 2 | 主题进入诊断的最低历史样本数 |
| `max-diagnostic-topics` | 5 | 单场保存的弱项主题上限 |
| `max-evidence-per-topic` | 5 | 单主题保存的历史证据上限 |
| `max-source-answers` | 200 | 创建会话最多读取的历史回答数 |
| `queued-stale-duration` | 2m | 排队任务重新投递阈值 |
| `processing-stale-duration` | 20m | 处理中任务失联阈值 |
| `recovery-interval` | 1m | 恢复扫描间隔 |
| `recovery-batch-size` | 50 | 单次恢复扫描数量 |

`TrainingProperties` 会在应用启动时校验证据容量和恢复时序。处理中超时必须大于排队超时，
各 Duration 必须为正数。

## 前端

- 新建入口：`/training`
- 会话恢复：`/training/{trainingId}`
- 轮询间隔：1.5 秒，使用递归 `setTimeout`，不会产生重叠请求。
- 普通任务固定轮询 taskId；仅在等待 SUMMARY 创建时查询 latest。
- SUMMARY 失败后停止后台轮询，保留服务端允许的重试按钮。

## 数据库迁移

- `V20260729__create_training_schema.sql`：会话和诊断主题。
- `V20260730__create_training_turn_tasks.sql`：训练轮次和可靠异步任务。
- `V20260731__create_training_summaries.sql`：聚合总结和 SUMMARY 任务类型。

生产环境使用 Flyway 迁移和 Hibernate `ddl-auto: validate`。不要手工执行 SQL 后绕过
`flyway_schema_history`。

## 静态验证入口

```bash
./gradlew :app:test --no-daemon
cd frontend
pnpm run test:training-polling
pnpm run build
```

限流或完整 Stream 消费集成测试需要真实 Redis。纯策略测试不依赖数据库、Redis 或 LLM。
