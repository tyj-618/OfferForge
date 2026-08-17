# Easy Offer Forge API 文档

Base URL：`/api`（开发环境 `http://localhost:8081`，Docker 部署经 nginx 反代 `http://localhost/api`）

## 通用约定

### 鉴权

除 `/api/auth/register`、`/api/auth/login`、`/api/health` 外，所有接口需携带：

```
Authorization: Bearer <token>
```

token 失效（code=40100）时，前端可凭 httpOnly refresh cookie 调用 `/api/auth/refresh` 静默续期。

### 统一响应体

```json
{ "code": 0, "message": "ok", "data": { } }
```

| code | 含义 |
| --- | --- |
| 0 | 成功 |
| 40000 | 请求参数错误（message 指明具体参数） |
| 40001 | 用户名或密码错误 |
| 40100 | token 失效 |
| 40400 | 资源不存在（含未映射的 API 路径，HTTP 状态同为 404） |
| 40900 | 资源状态冲突（如已有一场进行中的面试、开场/收尾环节不可跳过） |
| 42900 | 请求过于频繁（触发限流） |
| 50000 | 系统内部错误（不暴露技术细节） |
| 50300 | AI 服务暂时不可用 / LLM 超时 |
| 50301 | 服务暂时不可用（如数据库故障） |

### 限流（每用户滑动窗口，1 分钟）

| 路由 | 限额 |
| --- | --- |
| `POST /api/interview/{sessionId}/ask` | 10 次 |
| `POST /api/qa/ask` | 5 次 |
| `GET /api/report/**` | 3 次 |

超限返回 HTTP 429；SSE 路由以 `event:error` 帧返回 `{"code":42900,...}`。

---

## 认证 `/api/auth`

### POST /auth/register

注册账号（不直接下发 token，需随后调用 login）。

请求：`{ "username": "alice", "password": "123456" }`（密码至少 6 位）

响应 data：`{ "id": 1, "username": "alice", "nickname": "alice" }`

### POST /auth/login

请求：同 register。响应 data：

```json
{ "token": "eyJ...", "expiresIn": 7200, "refreshToken": "...", "refreshExpiresIn": 604800, "user": { "id": 1, "username": "alice", "nickname": "Candidate_1" } }
```

同时下发 httpOnly refresh cookie（路径 `/api/auth`）。

### POST /auth/refresh

无请求体，凭 refresh cookie 换取新 token。响应 data：`{ "token": "..." }`

### POST /auth/logout

清除 refresh cookie。

### GET /auth/me

当前登录用户摘要（顶栏展示用户名；刷新恢复时 token 在而登录缓存丢失的场景）。未登录返回 40100。

响应 data：`{ "id": 1, "username": "alice", "nickname": "Candidate_1" }`

---

## 知识库 `/api/knowledge`

### POST /knowledge/import

将内置 Java 后端题库导入当前用户知识库（幂等，已存在条目跳过）。

响应 data：`{ "total": 60, "inserted": 60, "skipped": 0 }`

### GET /knowledge/official

官方题库列表（全局共享只读），资源库页按分组筛选浏览。

响应 data：`[{ "id": 1, "question": "...", "answer": "...", "category": "Java基础", "difficulty": "简单" }]`

### POST /knowledge/batch-delete

批量删除本人上传的资料；仅删除归属本人的条目，其余静默跳过。

请求：`{ "ids": [1, 2, 3] }`

响应 data：`{ "deleted": 3 }`

---

## 问答 `/api/qa`

### POST /qa/ask

请求：`{ "question": "HashMap 的底层原理？" }`

响应 data：`{ "answer": "...", "referencedKnowledgeIds": [1, 3] }`

---

## 模拟面试 `/api/interview`

### POST /interview/start

开始一场面试。每用户同时仅允许一场进行中面试（否则 40900）。

请求：

```json
{ "position": "Java 后端工程师", "resumeId": 1, "mode": "training" }
```

`position` 可空（默认通用方向）；`resumeId` 可空（不关联简历则使用通用项目题）；
`mode`：`training`（训练模式）/ `practice`（实战模式），可空，缺省或非法值按 `practice` 处理。

响应 data：

```json
{
  "sessionId": "uuid",
  "openingMessage": "你好，我是今天的面试官……请先做个自我介绍",
  "status": { "...": "见 status 结构" }
}
```

### status 结构（公共）

```json
{
  "state": "BASICS",
  "phaseLabel": "基础考察",
  "difficultyLabel": "中等",
  "askedCount": 2,
  "plannedTotal": 8,
  "currentQuestion": "……",
  "currentQuestionFollowUp": false,
  "followUpsUsed": 0,
  "followUpLimit": 2,
  "lastScore": 6.5,
  "averageScore": 6.5,
  "mode": "training",
  "followUpChoiceRequired": false,
  "deepTrainingActive": false,
  "deepTrainingAsked": 0,
  "deepTrainingPassStreak": 0,
  "returnState": null
}
```

`state` 枚举：`OPENING | BASICS | PROJECT | DEEP | CLOSING | FINISHED | DEEP_TRAINING`；
`mode` 枚举：`training | practice`；
`followUpChoiceRequired`：已废弃（旧版低分选择卡标记），恒为 `false`，保留仅为响应体兼容；
实战模式过程免评分：`lastScore`/`averageScore` 为 `null`（评分仍完整入库供结束报告使用）；
`deepTrainingActive`：是否处于深度训练子流程；`deepTrainingAsked`：已出递进题数（上限 5）；
`deepTrainingPassStreak`：连续达标（≥6 分）题数，达 2 自动返回主面试；
`returnState`：深度训练进入前的主面试阶段（前端阶段进度条据此定位）。

### POST /interview/{sessionId}/ask （SSE）

提交回答，`Content-Type: application/json`，`Accept: text/event-stream`。

请求：`{ "message": "我的回答……" }`

事件流：

```
event:progress
data:正在评估你的回答…（阻塞节点状态帧：评估/追问生成/导师点评/出题，不进入对话记录，供前端思考动画展示动态文案）

event:message
data:面试官的下一段话（分块多次下发）

event:segment
data:（新气泡分段信号：训练模式导师反馈与后续追问/下一题分属两个对话气泡）

event:done
data:{"score":7,"evaluationComment":"……","evaluation":{...},"status":{...},"action":"NEW_QUESTION"}
```

`action` 枚举：`ADVANCE`（推进阶段）/ `NEW_QUESTION`（同阶段换题）/ `FOLLOW_UP`（追问）/ `FINISH`（`status.state=FINISHED` 时前端跳转报告），无评分轮次为 `null`。

训练模式回合流程：先流式输出导师人设反馈（按得分区间人性化点评：高分表扬/中分肯定指方向/低分宽慰鼓励，不透露分数），`event:segment` 分段后再流式输出追问/下一题（保证下一题始终在对话最下方）；done 载荷携带 `evaluation` 详细评估（实战模式为 null），供前端「具体分析」小窗展示：

```json
{
  "overall": 7, "accuracy": 8, "completeness": 7, "clarity": 7, "depth": 6,
  "keyPoints": ["……"], "missedPoints": ["……"], "wrongPoints": [],
  "feedback": "一句话点评",
  "goodPoints": ["回答中的亮点"],
  "badPoints": ["回答中的不足"],
  "improvedAnswer": "改进后的参考回答"
}
```

实战模式过程免评分：`score`/`evaluationComment`/`evaluation` 均为 `null`，面试官仅以极简中性过渡语衔接；无效回答（「不知道/不清楚」类短句）由服务端直接判低档。

错误帧：

```
event:error
data:{"code":40900,"message":"面试尚未开始"}
```

### POST /interview/{sessionId}/skip （SSE）

跳过当前题（计 0 分并推进状态机），无请求体，事件流契约与 ask 一致。
仅 `BASICS/PROJECT/DEEP` 环节可用，开场/收尾环节返回 40900；深度训练中拒绝跳过（40900，引导使用退出按钮）。

### POST /interview/{sessionId}/deep-training （SSE）

训练模式「深度训练」：围绕当前知识点进入 `DEEP_TRAINING` 子流程并发出第 1 道递进题，无请求体，事件流契约与 ask 一致。
递进题围绕知识点逐题递进（上限 5 题），连续 2 题 ≥6 分达标后自动返回主面试；不计入主流程已问题数/平均分。
仅训练模式且处于出题阶段（BASICS/PROJECT/DEEP）时可用，否则 error 事件返回 40900。

### POST /interview/{sessionId}/deep-training/exit （SSE）

主动退出深度训练：恢复主面试阶段并出下一题（题量已满则推进），无请求体，事件流契约与 ask 一致。
仅 `DEEP_TRAINING` 状态下可用，否则 error 事件返回 40900。

### POST /interview/{sessionId}/next-question （SSE）

训练模式「下一板块」：用户主动切换到同阶段下一题或下一阶段（不额外计分），无请求体，事件流契约与 ask 一致。
仅训练模式且处于出题阶段时可用，否则 error 事件返回 40900。

> 原 `POST /interview/{sessionId}/followup` 端点已移除，由深度训练子流程取代。

### POST /interview/{sessionId}/finish

结束面试：归档成绩、生成报告。响应 data 为最终 status。

### GET /interview/{sessionId}/status

查询当前状态（页面刷新恢复用）。响应 data 为 status 结构。

---

## 简历 `/api/resume`

### POST /resume

创建或更新（带 `id` 为更新）。结构化字段全空且携带 `rawText` 时由后端 LLM 解析回填。

请求：

```json
{
  "id": null,
  "name": "张三",
  "education": "……",
  "skills": "Java、Spring Boot……",
  "internships": "……",
  "selfIntroduction": "……",
  "projects": [
    {
      "projectName": "电商平台",
      "role": "后端负责人",
      "duration": "2025.03 - 2025.08",
      "description": "……",
      "techStack": "Spring Boot、Redis",
      "highlights": "……",
      "challenges": "……"
    }
  ],
  "rawText": "可选：简历原文"
}
```

响应 data：完整简历（含 `id`、`updatedAt`）。

### POST /resume/parse

纯文本解析预览（不落库）。请求 `{ "rawText": "……" }`，响应 data 为结构化字段。

### GET /resume/list

当前用户全部简历（按更新时间倒序）。响应 data：`[{ "id": 1, "name": "张三", "updatedAt": "..." }]`

### GET /resume/detail/{resumeId}

简历详情（仅本人）。

### GET /resume/{userId}

获取该用户最近更新的一份简历。

### GET /resume/{userId}/section/{section}

获取简历某部分纯文本，`section ∈ education | skills | projects | internships | selfIntroduction | all`。

### DELETE /resume/{id}

删除简历（仅本人）。

---

## 报告 `/api/report`

> 注意：该组接口每用户每分钟限 3 次。

### GET /report/{interviewId}

完整面试报告。响应 data：

```json
{
  "position": "Java 后端工程师",
  "interviewTime": "2026-08-14T10:00:00",
  "overallScore": 72.5,
  "rating": "良好",
  "totalQuestions": 8,
  "totalFollowUps": 3,
  "durationMinutes": 25,
  "basicsScore": 7.1,
  "projectScore": 6.8,
  "deepScore": 7.5,
  "avgAccuracy": 7.2,
  "avgCompleteness": 6.9,
  "avgClarity": 7.4,
  "avgDepth": 6.8,
  "strengths": ["……"],
  "weaknesses": ["……"],
  "suggestions": ["……"],
  "recommendedMaterials": [
    { "topic": "JVM GC", "reason": "……", "suggestedQuestion": "……" }
  ],
  "questionEvaluations": [
    {
      "questionIndex": 1,
      "question": "……",
      "userAnswer": "……",
      "score": 7.0,
      "feedback": "……",
      "followUp": false
    }
  ]
}
```

### GET /report/history?page=0&size=10

分页历史记录。响应 data：

```json
{
  "content": [
    { "interviewId": 12, "interviewTime": "...", "position": "...", "overallScore": 72.5, "status": "FINISHED" }
  ],
  "totalElements": 5
}
```

### GET /report/progress?limit=10

最近 N 次综合评分（进步曲线）。响应 data：`[{ "overallScore": 68.0 }, ...]`

---

## 健康检查 `/api/health`

### GET /health（免鉴权）

```json
{
  "status": "UP",
  "components": {
    "mysql": { "status": "UP", "latencyMs": 5 },
    "redis": { "status": "UP", "latencyMs": 2 },
    "elasticsearch": { "status": "DISABLED" },
    "llm": { "status": "UP", "latencyMs": 320 }
  }
}
```

- 核心组件（MySQL、LLM）任一 DOWN → 整体 `DOWN`
- 非核心组件（Redis、ES）DOWN → 整体 `DEGRADED`（自动降级内存/SQL）
- 未启用的组件为 `DISABLED`，不影响整体状态
