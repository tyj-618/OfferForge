# 🎯 Easy Offer Forge — AI 面试教练 Agent

> 基于 LLM 的模拟面试教练：围绕**你的个人知识库与简历**出题、实时追问、多维评分，并生成可追踪的进步报告。
> 把「刷题 → 模拟面试 → 复盘改进」的闭环产品化，练到拿到 Offer 为止。

🌐 **在线体验**：[https://easyofferforge.com](https://easyofferforge.com)（邮箱验证码注册即可使用）

---

## ✨ 项目亮点

| 亮点 | 说明 |
| --- | --- |
| 🧠 个性化出题 | 题目来自你的知识库与简历项目经历，而不是千篇一律的题库轮询 |
| 🎚️ 由浅入深自适应 | 连续高分自动升难度、答得吃力自动放缓，贴近真实面试官节奏 |
| 🗣️ 主动追问 | 基于回答内容的针对性追问，而非机械下一题 |
| 📊 可量化的进步 | 四维评分 + 雷达图 + 趋势曲线，每场报告可回溯 |
| 🔌 模型自由 | 官方模型（通义千问 / DeepSeek 免费档）开箱即用，也支持自带任意 OpenAI 兼容 Key |
| 🔒 隐私优先 | 密码哈希、Key 加密存储、个人数据严格隔离、全站 HTTPS |

## 🧩 核心功能（v1.0）

- **模拟面试**：训练 / 实战双模式，四环节状态机（基础考察 → 项目经历 → 深度追问 → 收尾总结），自适应出题、主动追问、断点恢复，训练模式支持「🎯 深入该模块」跳转专项突破
- **专项训练**：按知识分组强化，即时评分与导师反馈，刷新/暂离可恢复，训练报告支持打印
- **快捷提问**：基于个人知识库检索的结构化问答（先结论后展开，带引用）
- **资源库**：官方题库 **318 题**（五大方向 29 分组 + LeetCode Hot 100 算法题，附解题思路与复杂度），支持上传个人面经笔记（仅本人可见）、掌握度标记（绿勾/红叉影响出题权重）
- **简历驱动的项目题**：多份简历维护、LLM 解析、预览/编辑分离，面试围绕真实项目经历提问
- **多维评估报告**：维度评分、逐题明细、综合评价、评分趋势曲线，历史按训练/实战分页
- **短场保护**：作答不足 5 题的短场不消耗免费额度、不记录历史，误开零成本
- **账号体系**：邮箱验证码注册、忘记密码找回、登录限流与会话管理；唯一管理账号 + 管理台（用户统计/封禁、问题反馈处理）
- **问题反馈**：图文提交（最多 3 张截图），管理台分页查看，每日限 20 条防滥用
- **模型与计费**：设置页可选官方模型（系统默认 / 通义千问-Flash 免费档 / DeepSeek-V4-Flash 官方免费档），支持自带任意 OpenAI 兼容 Key；免费额度 + 余额计费已就绪（支付渠道审核中，充值页可查看余额与价目，充值操作待开放）

## 🛠 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Spring Boot 4 · Java 17 · Spring Data JPA · SSE 流式 |
| 前端 | Vue 3 · Vite 6 · ECharts · 原生 SSE 解析（零 UI 库依赖） |
| 存储 | MySQL 8.0（持久化）· Redis 7（会话/额度/限流，可降级内存）· Elasticsearch 8.x（向量检索，可降级 SQL） |
| AI | OpenAI 兼容协议（阿里云百炼通义千问 + DeepSeek 独立端点凭据路由）· 内置 mock provider 可离线开发 |
| 部署 | Docker Compose 五服务一键启动（多阶段构建，前端 nginx 托管） |

## 🏗 项目架构

```
┌────────────┐        ┌─────────────────────────────────────────┐
│  Vue3 SPA  │ ─HTTP─▶│  nginx / vite proxy                     │
│ (ECharts)  │ ◀─SSE──│                                         │
└────────────┘        └────────────────┬────────────────────────┘
                                       │ /api
                    ┌──────────────────▼──────────────────┐
                    │            Spring Boot              │
                    │  Auth / Interview / Training / QA   │
                    │  Resume / Report / Billing / Admin  │
                    ├─────────────────────────────────────┤
                    │ InterviewService（状态机编排）       │
                    │  ├─ InterviewQuestionBank（出题）    │
                    │  ├─ ProjectQuestionGenerator（简历题）│
                    │  ├─ FollowUpStrategy（追问决策）      │
                    │  ├─ EvaluationService（LLM 评分）     │
                    │  └─ StateTransitionStrategy（转移）   │
                    ├─────────────────────────────────────┤
                    │ AiModelClient（多端点凭据路由）       │
                    │ LlmCredentialResolver（用户Key优先）  │
                    │ RateLimitInterceptor（滑动窗口限流）  │
                    └───┬──────────────┬────────────┬─────┘
                        │              │            │
                    ┌───▼───┐     ┌────▼────┐  ┌────▼─────────┐
                    │ MySQL │     │  Redis  │  │Elasticsearch │
                    │ 持久化 │     │会话/限流 │  │向量检索(可选) │
                    └───────┘     └─────────┘  └──────────────┘
```

### 核心模块说明

**1. 面试状态机（`interview` 包）**：五环节 `OPENING → BASICS → PROJECT → DEEP → CLOSING → FINISHED`，每轮固定「评分 → 追问决策 → 难度调整 → 状态转移」四步编排，LLM 只负责单步评分与话术，转移条件全部是代码硬规则，面试不跑偏；难度采用迟滞控制（连续 N 次高分才升档）避免抖动震荡。

**2. 三层 Memory 设计**：工作记忆（12 条滑动窗口对话原文）支撑追问引用；会话记忆（状态机快照 + TTL）承载环节/难度/连续分；长期记忆（MySQL）沉淀知识库/简历/报告。每题作答后即被结构化评分记录替代原文，上下文长度恒定，20 轮面试不超窗。

**3. 评估系统**：提示词锚点式约束四维（准确/完整/清晰/深度）JSON 输出，解析失败自动重试、仍失败降级保守评分保证流程不中断；报告层按环节聚合生成雷达图、亮点/薄弱点与复习建议。

**4. 多端点凭据路由（`ai` 包）**：官方模型按价目目录的 `provider` 字段路由到各自端点（通义千问 / DeepSeek 独立 base-url + api-key），用户自带 Key 优先于官方凭据，未配置时安全降级。

**5. 健壮性与防滥用**：全局异常处理（500 不暴露细节）；按用户滑动窗口限流覆盖作答、开局/结束、问答、下单、反馈、报告等全部重开销与资金路径；单用户仅允许一场进行中面试/训练；短场额度退还防白嫖环路。

## 🚀 快速开始（本地开发）

### 前置条件

- JDK 17+、Node.js 18+
- Docker（仅用于拉起 MySQL/Redis 等中间件）

### 1. 启动中间件

```bash
docker compose up -d mysql redis
```

首次启动自动执行建表脚本（`src/main/resources/db/schema.sql`）。

### 2. 配置环境变量（可选）

```bash
cp .env.example .env   # Windows: copy .env.example .env
```

不配置也能跑：AI 默认 `mock` provider，离线可完整走通全流程。接入真实 LLM 时编辑 `.env`：

```properties
OFFERFORGE_AI_PROVIDER=openai-compatible
OFFERFORGE_AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
OFFERFORGE_AI_API_KEY=sk-xxxx
OFFERFORGE_AI_MODEL=qwen-plus
```

### 3. 启动后端（默认 8081）与前端（默认 5173）

```bash
./mvnw spring-boot:run       # Windows: mvnw.cmd spring-boot:run
```

```bash
cd frontend && npm install && npm run dev
```

打开 `http://localhost:5173`，注册账号 → 资源库导入官方题库 → 开始一场模拟面试。

### 4. 运行测试

```bash
./mvnw clean test            # 全量单元 + 集成测试（330+ 用例，含限流/额度/计费/题库导入）
```

## 📦 部署（Docker Compose 一键启动）

```bash
cp .env.example .env     # 按需填写 LLM API Key 等配置
docker compose up -d --build
```

五服务：`backend`（多阶段构建 JDK17 编译 → JRE17 运行）、`frontend`（Vite 构建 → nginx 静态 + API 反代 + SSE 透传）、`mysql:8.0`、`redis:7`、`elasticsearch:8.x`。全部数据卷持久化，敏感配置经 `.env` 注入（不入 git）。

```bash
curl http://localhost:8080/api/health      # {"status":"UP",...}
```

## 📚 API 文档

完整接口文档见 [docs/API.md](docs/API.md)。主要接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/register` · `/login` · `/logout` · `/refresh` | 认证（令牌 + refresh cookie） |
| GET | `/api/knowledge/categories` · POST `/api/knowledge/import` | 题库分组 / 导入官方题库 |
| POST | `/api/qa/ask-stream` | 知识问答（SSE 流式） |
| POST | `/api/interview/start` | 开始面试（岗位方向 + 可选简历 + 可选模型） |
| POST | `/api/interview/{id}/ask` | 提交回答（SSE 流式评分 + 追问） |
| POST | `/api/interview/{id}/finish` | 结束面试并归档报告（短场不归档） |
| POST | `/api/training/start` · `/{id}/answer` · `/{id}/finish` | 专项训练开局 / 作答 / 结束 |
| POST | `/api/resume` · `/api/resume/parse` | 保存简历 / LLM 解析预览 |
| GET | `/api/report/{id}` · `/history` · `/progress` | 报告 / 历史 / 进步曲线 |
| POST | `/api/feedback` · GET `/api/feedback/mine` | 提交图文反馈 / 本人历史 |
| GET | `/api/billing/status` · `/packages` · `/models` | 计费状态 / 充值档位 / 模型价目 |
| GET | `/api/admin/users` · `/admin/feedbacks` | 管理台（仅管理员） |
| GET | `/api/health` | 健康检查（免鉴权） |

统一响应体 `{ code, message, data }`，`code=0` 成功；`42900` 限流、`50300` AI 不可用。

## 💬 面试话术要点

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

### 难点四：计费与防滥用——免费额度、多模型路由与安全边界？

混合计费模式要同时防「白嫖」与「滥用」：短场（不足 5 题）不扣额度防误开损失，
但开局/结束接口加滑动窗口限流，堵住「开局即结束」循环套取退还、刷官方模型凭据的环路；
用户自带 Key 用 AES-256-GCM 加密落库，baseUrl 强制 HTTPS 并拒绝内网地址防 SSRF；
下单与模拟支付走行锁 + 状态条件更新防并发重复入账，资金路径全部登录态鉴权。

## License

仅供学习与面试展示使用。
