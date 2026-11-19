# SaaS 客户续费经营 Agent

项目问题调查、根因与处理效果记录见 [面试点.md](面试点.md)。

面向 B2B 订阅制 SaaS 客户成功团队的续费风险调查 Agent。系统每天扫描未来 90 天内到期的客户，通过规则引擎发现风险，再由 Agent 自主调用客户档案、产品使用、客服工单、账务和 RAG 知识工具，形成带证据的根因分析和处置草案。

与普通 RAG 问答的差异：

- RAG 回答“续费政策和处置手册怎么说”
- Agent 回答“这个客户为什么有风险”，并创建可跟踪的内部待办
- 7 天后系统重新计算使用、工单和账务指标，记录行动效果

## 核心流程

```text
续费风险扫描
  -> 规则分与风险因子
  -> Agent 规划下一步工具
  -> 客户 / 使用 / 工单 / 账单 / RAG 多源取证
  -> 根因分析与行动草案
  -> 人工确认
  -> 幂等创建内部待办
  -> 7 天后复查
```

Agent 状态机：

```text
CREATED -> PLANNING -> EXECUTING_TOOL -> ANALYZING
        -> WAITING_APPROVAL -> COMPLETED | FAILED
```

## 技术栈

- Java 21
- Spring Boot 3.4
- Spring MVC、JDBC、Actuator
- H2（开箱即用）或 MySQL
- Redis 分布式锁（可选）
- RocketMQ 异步调查队列（可选）
- Vue 3 + Vite
- OpenAI-compatible LLM；无模型密钥时使用确定性规划与降级分析
- OpenTelemetry + Langfuse Cloud 在线追踪
- 36 条金标准离线评测、可选独立 LLM-as-Judge

## 快速启动

环境要求：

- JDK 21
- Node.js 20+
- Maven 3.9+ 或 Maven Wrapper

前端已构建至 Spring Boot 静态资源目录。启动后端：

```powershell
.\mvnw.cmd spring-boot:run
```

打开 [http://localhost:8080](http://localhost:8080)。

前端开发模式：

```powershell
cd frontend
npm install
npm run dev
```

前端生产构建：

```powershell
cd frontend
npm run build
```

## 合成数据

首次启动会生成 36 个客户和 180 天历史数据，包含 6 类账户：

- 健康账户
- 功能采用不足
- 客服工单阻塞
- 账务异常
- 关键联系人失联
- 真实流失

固定随机种子保证演示结果可复现。演示账户 `ACC-003` 是工单阻塞场景，`ACC-004` 是账务阻塞场景。

Demo 数据通过 `seed_metadata` 管理版本。当前版本为 `demo-v2`，版本变化时会自动重建 `ACC-*` 合成账户及其使用、工单和账单数据。

## LLM 配置

默认 `deterministic` 模式不调用外部模型，可直接运行和测试：

```text
app.agent.llm.mode=deterministic
```

使用 OpenAI-compatible 服务：

```powershell
$env:LLM_BASE_URL="https://your-provider.example/v1"
$env:LLM_API_KEY="your-key"
$env:LLM_MODEL="your-model"

.\mvnw.cmd spring-boot:run -Dspring-boot.run.arguments="--app.agent.llm.mode=openai"
```

模型只负责选择已注册工具和生成结构化分析。工具白名单、状态机、最大调用次数、审批、幂等和审计全部由 Java 后端执行。

## Agent 评测

评测中心包含 30 个风险正例和 6 个健康负例，直接复用 Agent 执行内核，但从版本化内存 fixture 读取数据，不访问线上 H2/MySQL，也不会创建待办或修改线上调查。

每次运行会记录 `fixtureVersion`、SHA-256 `fixtureHash` 和 `dataStatus`。Fixture 校验失败时数据状态为 `BLOCKED`，不能判定为 PASS。

运行全部确定性评测：

```powershell
.\mvnw.cmd -Peval test
```

CI 使用的阻断命令：

```powershell
.\mvnw.cmd -Peval verify
```

报告输出到：

```text
target/evaluation/latest.json
target/evaluation/latest.md
```

关键阻断阈值：

| 指标 | 阈值 |
|---|---:|
| 完成率 | >= 95% |
| 根因 Top-1 准确率 | >= 80% |
| 工具选择准确率 | >= 90% |
| 证据覆盖率 | >= 90% |
| 行动策略通过率 | = 100% |
| 严重安全违规 | = 0 |

默认使用无需网络的本地规则 Judge。配置以下环境变量后自动切换为独立 LLM Judge：

```powershell
$env:LLM_JUDGE_MODE="openai"
$env:LLM_JUDGE_BASE_URL="https://your-provider.example/v1"
$env:LLM_JUDGE_API_KEY="your-key"
$env:LLM_JUDGE_MODEL="your-judge-model"
```

## Langfuse

Java 通过 OpenTelemetry HTTP/Protobuf 发送 Trace，不使用 gRPC。Langfuse 默认关闭，不会影响本地启动或测试。

```powershell
$env:LANGFUSE_ENABLED="true"
$env:LANGFUSE_HOST="https://cloud.langfuse.com"
$env:LANGFUSE_PUBLIC_KEY="pk-lf-..."
$env:LANGFUSE_SECRET_KEY="sk-lf-..."
$env:LANGFUSE_PROJECT_ID="your-project-id"
$env:LANGFUSE_ENVIRONMENT="development"
$env:LANGFUSE_DATASET_SYNC_ENABLED="true"
```

OTLP Endpoint：

```text
https://cloud.langfuse.com/api/public/otel/v1/traces
```

每次线上调查会记录 Agent 根 Span、Planner/Analyzer、工具调用、Token、耗时和追溯信息；离线评测会按 Experiment 分组，并通过 Scores API 上传指标。启用 Dataset 同步时，Git fixture 会以稳定 Case ID 镜像到 Langfuse，但 Langfuse 不可用不会阻断本地评测。

## MySQL、Redis 与 RocketMQ

默认使用本地 H2 文件数据库，不需要安装任何基础设施。

MySQL：

```powershell
$env:MYSQL_URL="jdbc:mysql://localhost:3306/renewal_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="your-password"

.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=mysql
```

Redis 分布式扫描锁：

```powershell
.\mvnw.cmd spring-boot:run -Dspring-boot.run.arguments="--app.lock.mode=redis"
```

RocketMQ 调查队列：

```powershell
.\mvnw.cmd spring-boot:run -Dspring-boot.run.arguments="--app.queue.mode=rocketmq --app.agent.inline=false --app.queue.rocketmq.namesrv-addr=127.0.0.1:9876"
```

## API

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/overview` | 总览指标 |
| GET | `/api/risk-candidates` | 风险客户队列 |
| POST | `/api/jobs/risk-scan` | 手动触发风险扫描 |
| POST | `/api/accounts/{id}/investigations` | 发起 Agent 调查 |
| GET | `/api/investigations/{id}` | 查看分析、证据与任务 |
| GET | `/api/investigations/{id}/events` | SSE 执行轨迹 |
| POST | `/api/investigations/{id}/approve-task` | 确认并幂等创建待办 |
| POST | `/api/tasks/{id}/follow-up` | 立即模拟到期复查 |
| GET | `/api/evaluations/capabilities` | Judge 与 Langfuse 配置状态 |
| GET | `/api/evaluations/cases` | 当前金标准用例 |
| POST | `/api/evaluations/runs` | 创建异步评测运行 |
| GET | `/api/evaluations/runs/{id}` | 查看评测指标与用例 |
| GET | `/api/evaluations/latest` | 最近一次评测 |
| POST | `/api/evaluations/runs/{id}/judge` | 对已有结果运行 Judge |
| GET | `/actuator/health` | 健康检查 |

## 测试

```powershell
.\mvnw.cmd test
```

测试覆盖：

- 36 个合成客户与风险候选生成
- 风险阈值和账务风险保底规则
- 完整 Agent 工具循环和证据留存
- 审批幂等建单
- 到期复查结果
- 评测根因、工具、证据、行动策略和健康负例
- OpenTelemetry 父子 Span 与 Langfuse Experiment 属性
- Langfuse Scores API 请求格式、鉴权和失败降级

## CI

GitHub Actions 工作流位于 `.github/workflows/ci.yml`，在 Pull Request、`main` 推送和版本 Tag 上执行：

- 安装 Java 21 和 Node.js 22
- 构建 Vue 前端
- 执行后端测试
- 执行 `./mvnw -Peval verify`
- 打包 Spring Boot JAR
- 上传 JAR、JSON 和 Markdown 评测报告

## 安全边界

- Agent 只拥有只读业务工具。
- 外部消息、价格修改、折扣和真实 CRM 写入不在 v1 范围内。
- 唯一业务写操作是用户确认后创建内部任务。
- 模型输出经过结构校验；非法输出会降级为基于规则的分析。
- 工具数据按不可信输入处理，不允许生成任意 SQL 或动态代码。
