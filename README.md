# 🎯 OfferForge — AI 面试教练 Agent

> 一句话定位：基于 LLM 的模拟面试教练，围绕你的个人知识库出题、实时追问、多维评分，并生成可追踪的进步报告。

## 核心功能

- **问答练习**：基于个人知识库的 AI 技术问答
- **模拟面试**：状态机驱动的五环节完整面试（开场 → 基础题 → 项目题 → 深度追问 → 收尾），SSE 流式输出
- **动态追问与难度调整**：根据回答评分自动触发追问、升降难度
- **简历驱动的项目题**：LLM 解析简历，围绕你的真实项目经历出题
- **多维评估报告**：准确性/完整性/清晰度/深度四维评分、雷达图、亮点与薄弱点、改进建议、推荐复习材料
- **进步曲线**：历次面试评分趋势追踪
- **跳过此题 / 提前结束**：贴近真实面试的交互控制

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Spring Boot 4.0.6 · Java 17 · Spring Data JPA · SSE |
| 前端 | Vue 3 · Vite 6 · ECharts · 原生 SSE 解析（无 UI 库） |
| 存储 | MySQL 8.0（持久化）· Redis 7（会话/限流，可降级内存）· Elasticsearch 8.x（向量检索，可降级 SQL） |
| AI | OpenAI 兼容接口（如阿里云百炼 qwen-plus）· 内置 mock provider 可离线开发 |
| 部署 | Docker Compose 五服务一键启动（多阶段构建） |

## 快速开始（本地开发，10 分钟）

### 前置条件

- JDK 17+、Node.js 18+
- Docker（仅用于拉起 MySQL/Redis 等中间件）

### 1. 启动中间件

```bash
docker compose up -d mysql redis
```

首次启动会自动执行建表脚本（`src/main/resources/db/schema.sql`）。

### 2. 配置环境变量（可选）

```bash
cp .env.example .env   # Windows: copy .env.example .env
```

不配置也能跑：AI 默认 `mock` provider（离线可完整走通全流程）。
接入真实 LLM 时编辑 `.env`：

```properties
OFFERFORGE_AI_PROVIDER=openai-compatible
OFFERFORGE_AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
OFFERFORGE_AI_API_KEY=sk-xxxx
OFFERFORGE_AI_MODEL=qwen-plus
```

本地启动后端会读取系统环境变量；也可直接在 IDE 的运行配置中设置。

### 3. 启动后端（默认 8081）

```bash
./mvnw spring-boot:run       # Windows: mvnw.cmd spring-boot:run
```

### 4. 启动前端（默认 5173，已配置 /api 代理）

```bash
cd frontend
npm install
npm run dev
```

打开 `http://localhost:5173`，注册账号 → 问答练习页点击「导入内置知识库」→ 开始模拟面试。

### 5. 运行测试

```bash
./mvnw clean test            # 125 个测试（单元 + 集成）
```

## 项目架构

```
┌────────────┐        ┌─────────────────────────────────────────┐
│  Vue3 SPA  │ ─HTTP─▶│  nginx / vite proxy                     │
│ (ECharts)  │ ◀─SSE──│                                         │
└────────────┘        └────────────────┬────────────────────────┘
                                       │ /api
                    ┌──────────────────▼──────────────────┐
                    │            Spring Boot              │
                    │  AuthController  InterviewController│
                    │  QaController    ReportController   │
                    │  ResumeController HealthController  │
                    ├─────────────────────────────────────┤
                    │ InterviewService（状态机编排）       │
                    │  ├─ InterviewQuestionBank（出题）    │
                    │  ├─ ProjectQuestionGenerator（简历题）│
                    │  ├─ FollowUpStrategy（追问决策）      │
                    │  ├─ EvaluationService（LLM 评分）     │
                    │  └─ StateTransitionStrategy（转移）   │
                    ├─────────────────────────────────────┤
                    │ AiModelClient（mock / OpenAI 兼容）   │
                    │ ToolRegistry（Agent 工具注册）        │
                    │ RateLimitInterceptor（滑动窗口限流）  │
                    └───┬──────────────┬────────────┬─────┘
                        │              │            │
                    ┌───▼───┐     ┌────▼────┐  ┌────▼─────────┐
                    │ MySQL │     │  Redis  │  │Elasticsearch │
                    │ 持久化 │     │会话/限流 │  │向量检索(可选) │
                    └───────┘     └─────────┘  └──────────────┘
```

## 核心模块说明

### 1. 面试状态机（`interview` 包）

五环节状态机：`OPENING → BASICS → PROJECT → DEEP → CLOSING → FINISHED`。
每轮对话由 `InterviewService` 编排：**评分 → 追问决策 → 难度调整 → 状态转移**：

- 回答由 `EvaluationService` 调用 LLM 输出结构化评分（四维 + 总分 + 点评）
- `FollowUpStrategy` 根据分数与已追问次数决定是否追问（上限可配，默认 2 次）
- 连续高分升难度、连续低分降难度（`consecutiveHighScores/LowScores`）
- `StateTransitionStrategy` 在题量用尽或环节结束时推进状态
- 支持「跳过此题」（计 0 分推进）与提前结束（按已作答题目出报告）

### 2. 三层 Memory 设计

| 层 | 载体 | 职责 |
| --- | --- | --- |
| 工作记忆 | `InterviewMessageStore` 滑动窗口（默认 12 条） | 最近对话原文，组装进 LLM 上下文 |
| 会话记忆 | `InterviewSessionStore`（`InterviewContext`） | 状态机快照、当前题、难度、连续分数，TTL 过期 |
| 长期记忆 | MySQL（知识库/简历/报告归档） | 跨面试沉淀，驱动项目题与进步曲线 |

**上下文压缩策略**：每题作答后，评分与点评以结构化字段存入 `QuestionRecord`，不再占用对话窗口；窗口外的历史以「环节小结 + 分数」的形式体现在报告与提示词中，保证 LLM 上下文长度恒定。

### 3. 评估系统

- 提示词强约束 JSON 输出（accuracy/completeness/clarity/depth 各 0-10 分 + 点评）
- JSON 解析失败自动重试一次，仍失败则降级为保守评分，保证面试不中断
- 报告层对各题分数按环节聚合，生成雷达图、亮点/薄弱点、改进建议与复习材料

### 4. 工具调用（`tool` 包）

`AgentTool` + `ToolRegistry` 提供统一的工具注册与上下文注入（`ToolContext`），
知识库检索、简历读取等能力以工具形式暴露给 Agent 编排层。

### 5. 健壮性设计

- 全局异常处理：LLM 超时 503、参数校验 400、未知异常 500 不暴露细节
- 限流：Redis（或内存）滑动窗口，`/api/interview/ask` 10 次/分、`/api/qa/ask` 5 次/分、`/api/report` 3 次/分；单用户仅允许一场进行中面试
- 降级：Redis 不可用降级内存存储；ES 不可用降级 SQL 模糊检索
- 健康检查 `GET /api/health`：四组件探测 + UP/DEGRADED/DOWN 聚合

## API 文档

完整接口文档见 [docs/API.md](docs/API.md)。主要接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/register` · `/login` · `/logout` · `/refresh` | 认证（JWT + refresh cookie） |
| POST | `/api/knowledge/import` | 导入内置知识库 |
| POST | `/api/qa/ask` | 知识问答 |
| POST | `/api/interview/start` | 开始面试（岗位方向 + 可选简历） |
| POST | `/api/interview/{sessionId}/ask` | 提交回答（SSE 流式） |
| POST | `/api/interview/{sessionId}/skip` | 跳过当前题（SSE，计 0 分） |
| POST | `/api/interview/{sessionId}/finish` | 结束面试并归档报告 |
| GET | `/api/interview/{sessionId}/status` | 查询面试状态 |
| POST | `/api/resume` · `/api/resume/parse` | 保存简历 / LLM 解析预览 |
| GET | `/api/resume/list` · `/detail/{id}` | 简历列表 / 详情 |
| GET | `/api/report/{interviewId}` · `/history` · `/progress` | 报告 / 历史 / 进步曲线 |
| GET | `/api/health` | 健康检查（免鉴权） |

统一响应体 `{ code, message, data }`，`code=0` 为成功；`42900` 限流、`50300` AI 不可用、`50301` 服务不可用。

## 部署（Docker Compose 一键启动）

```bash
cp .env.example .env     # 按需填写 LLM API Key
docker compose up -d --build
```

五服务：`backend`（多阶段构建 JDK17 编译 → JRE17 运行，8080）、`frontend`（node 编译 → nginx 静态 + API 反代 + SSE，80）、`mysql:8.0`、`redis:7`、`elasticsearch:8.x`（单节点、关安全认证）。全部数据卷持久化，配置经 `.env` 注入（不入 git）。

验证：

```bash
curl http://localhost:8080/api/health      # {"status":"UP",...}
# 浏览器访问 http://localhost 使用前端
```

常用运维命令：`docker compose logs -f backend`、`docker compose down`（加 `-v` 清除数据卷）。

## 面试话术要点

> 这个项目解决了什么问题：把「刷题 + 模拟面试 + 复盘」闭环产品化——基于候选人自己的知识库和简历出题，面试过程实时追问与动态调难，结束后给出可量化、可追踪的改进报告。

### 难点一：状态机编排——面试流程如何控制、难度如何动态调整？

面试是强流程场景，不能让 LLM 自由发挥。我用五环节状态机把流程收敛为确定性转移：
每轮固定「评分 → 追问决策 → 难度调整 → 状态转移」四步，LLM 只负责单步的评分与话术生成，
转移条件（题量、追问次数、环节结束）全部是代码里的硬规则，保证面试不跑偏。
难度调整是迟滞控制：连续 N 次高分才升档、连续低分才降档，避免单次分数抖动导致难度震荡；
追问有次数上限，防止在一个点上无限深挖而拖垮整体节奏。

### 难点二：Memory 设计——三层 Memory 的职责与上下文压缩？

LLM 上下文有限，不能把整场面试原文塞进去。我分了三层：
工作记忆是滑动窗口（12 条最近对话），保证追问能引用刚才的回答；
会话记忆是状态机快照（当前环节、难度、连续得分），每轮更新、带 TTL；
长期记忆落库（知识库、简历、历史报告），支撑项目题生成和进步曲线。
压缩策略的关键是「结构化替代原文」：每题作答后立即被评分+点评的结构化记录替代，
原始回答只在窗口存活期内保留，这样上下文长度恒定，面试 20 轮也不会超窗。

### 难点三：评估系统——多维评分如何设计、如何减少 LLM 评分偏差？

单分数没有诊断价值，我拆成准确性/完整性/清晰度/深度四维，提示词里给每一维明确的锚点描述
（什么样算 8 分、什么样算 4 分），并要求输出严格 JSON。减少偏差的手段：
① 锚点式评分标准压缩模型自由发挥空间；② JSON 解析失败自动重试，仍失败降级保守评分，
保证流程可用；③ 报告层按环节聚合看趋势而非纠结单题分数；④ 难度动态调整后，
分数解释结合当时的难度档位，避免「题目变难分数下降」被误读为能力退步。

## License

仅供学习与面试展示使用。
