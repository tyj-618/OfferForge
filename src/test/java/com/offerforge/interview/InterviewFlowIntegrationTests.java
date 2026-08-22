package com.offerforge.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.email.EmailVerificationCodeStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 面试流程端到端测试（test profile：mock provider、各阶段题量上限=1）。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InterviewFlowIntegrationTests {

    private static final String LONG_ANSWER = "我对这个问题有比较深入的理解，从底层原理到工程实践都做过总结，包括核心流程、常见陷阱以及对应的优化方案。";

    @LocalServerPort
    private int port;

    @Autowired
    private EmailVerificationCodeStore codeStore;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fullInterviewFlowAdvancesThroughAllPhases() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);

        // start：返回 sessionId 与开场白，状态 OPENING
        JsonNode start = post("/api/interview/start", token, Map.of());
        assertCode(start, 0);
        String sessionId = start.at("/data/sessionId").asText();
        assertThat(sessionId).isNotBlank();
        assertThat(start.at("/data/openingMessage").asText()).contains("自我介绍");
        assertThat(start.at("/data/status/state").asText()).isEqualTo("OPENING");
        assertThat(start.at("/data/status/plannedTotal").asInt()).isEqualTo(3);
        assertThat(start.at("/data/status/remaining").asInt()).isEqualTo(3);

        // 第 1 轮：OPENING → BASICS（自我介绍不评分，直接出题）
        String sse1 = ask(sessionId, token, "我熟悉 Java 后端开发，做过电商项目。");
        assertThat(sse1).contains("event:message").contains("模拟面试官").contains("event:done");
        assertThat(sse1).contains("\"action\":\"ADVANCE\"").contains("\"state\":\"BASICS\"");

        // status：展示当前阶段、当前题与剩余题数（供前端渲染）
        JsonNode status = get("/api/interview/" + sessionId + "/status", token);
        assertCode(status, 0);
        assertThat(status.at("/data/phaseLabel").asText()).isEqualTo("基础考察");
        assertThat(status.at("/data/currentQuestion").asText()).isNotBlank();
        assertThat(status.at("/data/askedCount").asInt()).isZero();
        // 基础题已发出（待作答），剩余 = PROJECT 1 + DEEP 1
        assertThat(status.at("/data/remaining").asInt()).isEqualTo(2);

        // 第 2 轮：长回答评分 8 照常入库（status.history 含分供报告/回放），实战模式过程免评分：
        // done 顶层 score/点评/评估均为 null → 推进 PROJECT
        String sse2 = ask(sessionId, token, LONG_ANSWER);
        JsonNode done2 = doneOf(sse2);
        assertThat(done2.at("/score").isNull()).isTrue();
        assertThat(done2.at("/evaluationComment").isNull()).isTrue();
        assertThat(done2.at("/evaluation").isNull()).isTrue();
        assertThat(sse2).contains("\"action\":\"ADVANCE\"").contains("\"state\":\"PROJECT\"");

        // 第 3 轮：PROJECT → DEEP
        String sse3 = ask(sessionId, token, LONG_ANSWER);
        assertThat(sse3).contains("\"action\":\"ADVANCE\"").contains("\"state\":\"DEEP\"");

        // 第 4 轮：DEEP → CLOSING（收尾话术直接给出统计）
        String sse4 = ask(sessionId, token, LONG_ANSWER);
        assertThat(sse4).contains("\"state\":\"CLOSING\"").contains("考察环节已结束");

        // 第 5 轮：CLOSING 回复后进入 FINISHED
        String sse5 = ask(sessionId, token, "谢谢面试官，期待反馈。");
        assertThat(sse5).contains("\"action\":\"FINISH\"").contains("\"state\":\"FINISHED\"");

        // 已结束的会话继续作答 → SSE error 事件
        String sse6 = ask(sessionId, token, "还能继续吗？");
        assertThat(sse6).contains("event:error").contains("面试已结束");

        // finish：结束面试并返回综合反馈报告（3 题均 8 分 → 综合分 80）
        JsonNode finish = post("/api/interview/" + sessionId + "/finish", token, Map.of());
        assertCode(finish, 0);
        assertThat(finish.at("/data/totalQuestions").asInt()).isEqualTo(3);
        assertThat(finish.at("/data/overallScore").asDouble()).isEqualTo(80.0);
        assertThat(finish.at("/data/questionEvaluations").size()).isEqualTo(3);

        // 报告可通过 GET 重复查询，且重复 finish 幂等
        JsonNode report = get("/api/report/" + sessionId, token);
        assertCode(report, 0);
        assertThat(report.at("/data/interviewId").asText()).isEqualTo(sessionId);
        assertCode(post("/api/interview/" + sessionId + "/finish", token, Map.of()), 0);
    }

    @Test
    void lowScoreTriggersFollowUpsThenAdvancesWhenExhausted() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        String sessionId = post("/api/interview/start", token, Map.of()).at("/data/sessionId").asText();
        ask(sessionId, token, "自我介绍：熟悉 Java。");

        // 第 1 次低分 → 追问（同知识点换角度）；实战模式过程免评分，done 载荷不含分数
        String sse1 = ask(sessionId, token, "嗯。");
        assertThat(sse1).contains("\"score\":null").contains("\"action\":\"FOLLOW_UP\"").contains("模拟追问");
        JsonNode status1 = get("/api/interview/" + sessionId + "/status", token);
        assertThat(status1.at("/data/state").asText()).isEqualTo("BASICS");
        assertThat(status1.at("/data/followUpsUsed").asInt()).isEqualTo(1);
        // 追问标识字段（供前端渲染「🔄 追问 1/2」标签）
        assertThat(status1.at("/data/currentQuestionFollowUp").asBoolean()).isTrue();
        assertThat(status1.at("/data/followUpLimit").asInt()).isEqualTo(2);

        // 第 2 次低分 → 追问（上限 2 次）；连续 2 次低分后难度降为简单
        String sse2 = ask(sessionId, token, "不太清楚。");
        assertThat(sse2).contains("\"action\":\"FOLLOW_UP\"");
        JsonNode status2 = get("/api/interview/" + sessionId + "/status", token);
        assertThat(status2.at("/data/followUpsUsed").asInt()).isEqualTo(2);
        assertThat(status2.at("/data/difficultyLabel").asText()).isEqualTo("简单");

        // 第 3 次低分（“不会”命中无效回答检测，服务端直接判低档）：追问已用尽且阶段题量达上限 → 推进 PROJECT
        String sse3 = ask(sessionId, token, "不会。");
        assertThat(sse3).contains("\"action\":\"ADVANCE\"").contains("\"state\":\"PROJECT\"");
    }

    @Test
    void askEmitsProgressFramesBeforeMessageFrames() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        String sessionId = post("/api/interview/start", token, Map.of()).at("/data/sessionId").asText();

        // 首轮（OPENING→BASICS）：出题前即下发状态帧，且先于任何对话内容帧
        String sse1 = ask(sessionId, token, "自我介绍：熟悉 Java。");
        assertThat(sse1).contains("event:progress").contains("正在准备下一题…");
        assertThat(sse1.indexOf("event:progress")).isLessThan(sse1.indexOf("event:message"));

        // 评估轮：评估是长阻塞 LLM 调用，评估状态帧先于追问/下一题内容帧
        String sse2 = ask(sessionId, token, LONG_ANSWER);
        assertThat(sse2).contains("正在评估你的回答…");
        assertThat(sse2.indexOf("正在评估你的回答…")).isLessThan(sse2.indexOf("event:message"));
    }

    @Test
    void interviewEndpointsEnforceAuthAndOwnership() throws Exception {
        assertCode(post("/api/interview/start", null, Map.of()), 40100);

        // 无 token 调 ask：错误以 SSE error 事件返回（内容与 JSON 接口同一套错误码）
        String sseNoToken = askRaw("no-session", null, "任意回答");
        assertThat(sseNoToken).contains("event:error").contains("40100");

        String tokenA = newUser();
        String sessionId = post("/api/interview/start", tokenA, Map.of()).at("/data/sessionId").asText();

        // 空白回答 → 参数校验错误（SSE error 事件）
        assertThat(askRaw(sessionId, tokenA, " ")).contains("event:error").contains("40000");

        // 其他用户访问他人会话 → 40300
        String tokenB = newUser();
        assertCode(get("/api/interview/" + sessionId + "/status", tokenB), 40300);
        assertCode(post("/api/interview/" + sessionId + "/finish", tokenB, Map.of()), 40300);

        // 会话不存在 → 40400
        assertCode(get("/api/interview/missing-session/status", tokenA), 40400);
    }

    @Test
    void trainingModeDeepTrainingAchievesAndReturnsToInterview() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);

        // start 携带 mode=training，status 回显模式
        JsonNode start = post("/api/interview/start", token, Map.of("mode", "training"));
        assertCode(start, 0);
        String sessionId = start.at("/data/sessionId").asText();
        assertThat(start.at("/data/status/mode").asText()).isEqualTo("training");
        ask(sessionId, token, "自我介绍：熟悉 Java。");

        // 低分 → FOLLOW_UP：先流导师反馈气泡（segment 分段）再流追问话术，done 携带详细评估
        String sse1 = ask(sessionId, token, "嗯。");
        assertThat(sse1).contains("\"score\":3").contains("\"action\":\"FOLLOW_UP\"")
                .contains("【导师反馈】").contains("模拟追问")
                .contains("\"followUpChoiceRequired\":false")
                .contains("\"goodPoints\"").contains("\"improvedAnswer\"");
        // 消息顺序：导师反馈在前，追问（下一题）在后
        assertThat(sse1.indexOf("【导师反馈】")).isLessThan(sse1.indexOf("模拟追问"));
        JsonNode status1 = get("/api/interview/" + sessionId + "/status", token);
        assertThat(status1.at("/data/followUpsUsed").asInt()).isEqualTo(1);
        assertThat(status1.at("/data/currentQuestionFollowUp").asBoolean()).isTrue();

        // “深度训练”：用户主动进入子流程并发出第 1 道递进题
        String sse2 = ssePost("/api/interview/" + sessionId + "/deep-training", token);
        assertThat(sse2).contains("深度训练").contains("event:done");
        JsonNode status2 = get("/api/interview/" + sessionId + "/status", token);
        assertThat(status2.at("/data/state").asText()).isEqualTo("DEEP_TRAINING");
        assertThat(status2.at("/data/deepTrainingActive").asBoolean()).isTrue();
        assertThat(status2.at("/data/deepTrainingAsked").asInt()).isEqualTo(1);
        assertThat(status2.at("/data/returnState").asText()).isEqualTo("BASICS");
        assertThat(status2.at("/data/followUpChoiceRequired").asBoolean()).isFalse();
        // 深度训练中掌握度标记被拒绝（引导使用退出按钮）
        assertThat(ssePost("/api/interview/" + sessionId + "/mastered", token))
                .contains("event:error").contains("40900");

        // 第 1 道递进题达标（长回答 → 8 分 ≥ 6）：继续出第 2 题，连续达标计数 1
        String sse3 = ask(sessionId, token, LONG_ANSWER);
        assertThat(sse3).contains("\"score\":8")
                .contains("\"deepTrainingPassStreak\":1").contains("\"deepTrainingAsked\":2");

        // 连续第 2 题达标 → 达标话术 + 返回主面试（BASICS 题量已满 → 推进 PROJECT 出新题）
        String sse4 = ask(sessionId, token, LONG_ANSWER);
        assertThat(sse4).contains("\"score\":8").contains("已连续")
                .contains("\"action\":\"ADVANCE\"").contains("\"state\":\"PROJECT\"");
        JsonNode status4 = get("/api/interview/" + sessionId + "/status", token);
        assertThat(status4.at("/data/deepTrainingActive").asBoolean()).isFalse();
        // 深度训练题不计入主流程已问题数（仍为基础阶段那 1 题）
        assertThat(status4.at("/data/askedCount").asInt()).isEqualTo(1);
    }

    @Test
    void trainingModeDeepTrainingExitReturnsToInterview() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        String sessionId = post("/api/interview/start", token, Map.of("mode", "training"))
                .at("/data/sessionId").asText();
        ask(sessionId, token, "自我介绍：熟悉 Java。");
        // 低分触发追问后，用户主动进入深度训练
        ask(sessionId, token, "嗯。");
        ssePost("/api/interview/" + sessionId + "/deep-training", token);

        // 主动退出：回到主面试（BASICS 题量已满 → 推进 PROJECT）
        String exit = ssePost("/api/interview/" + sessionId + "/deep-training/exit", token);
        assertThat(exit).contains("退出深度训练")
                .contains("\"action\":\"ADVANCE\"").contains("\"state\":\"PROJECT\"");
        JsonNode status = get("/api/interview/" + sessionId + "/status", token);
        assertThat(status.at("/data/deepTrainingActive").asBoolean()).isFalse();

        // 非深度训练状态再次退出 → error 事件
        assertThat(ssePost("/api/interview/" + sessionId + "/deep-training/exit", token)).contains("event:error");
    }

    @Test
    void trainingModeStreamsMentorFeedbackBeforeNextQuestion() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        String sessionId = post("/api/interview/start", token, Map.of("mode", "training"))
                .at("/data/sessionId").asText();
        ask(sessionId, token, "自我介绍：熟悉 Java。");

        // 高分 → 阶段题量已满推进：导师反馈气泡先流，segment 分段后再流下一阶段新题
        String sse = ask(sessionId, token, LONG_ANSWER);
        assertThat(sse).contains("【导师反馈】").contains("模拟面试官").contains("event:segment")
                .contains("\"score\":8").contains("\"action\":\"ADVANCE\"").contains("\"state\":\"PROJECT\"");
        assertThat(sse.indexOf("【导师反馈】")).isLessThan(sse.indexOf("模拟面试官"));
        // 导师反馈不含评分字样（人性化点评不透露分数）
        String mentorFrame = sse.substring(sse.indexOf("【导师反馈】"), sse.indexOf("模拟面试官"));
        assertThat(mentorFrame).doesNotContain("得分").doesNotContain("评分");
    }

    @Test
    void practiceModeRejectsChoiceEndpoints() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        // 缺省模式 = practice：选择类端点（深度训练/退出/下一板块）均走 error 事件
        String sessionId = post("/api/interview/start", token, Map.of()).at("/data/sessionId").asText();
        assertThat(ssePost("/api/interview/" + sessionId + "/deep-training", token)).contains("event:error");
        assertThat(ssePost("/api/interview/" + sessionId + "/deep-training/exit", token)).contains("event:error");
        assertThat(ssePost("/api/interview/" + sessionId + "/next-question", token)).contains("event:error");
    }

    private String newUser() throws Exception {
        String username = "interview_user_" + System.nanoTime();
        String email = username.toLowerCase() + "@test.local";
        codeStore.saveCode(email, "135790");
        assertCode(post("/api/auth/register", null,
                Map.of("email", email, "code", "135790", "username", username, "password", "123456")), 0);
        JsonNode login = post("/api/auth/login", null, Map.of("username", username, "password", "123456"));
        assertCode(login, 0);
        return login.at("/data/token").asText();
    }

    /**
     * SSE 问答：返回完整事件流文本（event:message 分块 + event:done 载荷）。
     */
    private String ask(String sessionId, String token, String message) throws Exception {
        return askRaw(sessionId, token, message);
    }

    private String askRaw(String sessionId, String token, String message) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/interview/" + sessionId + "/ask"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of("message", message)), StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    /**
     * 无请求体的 SSE 端点（mastered / dontknow / deep-training / deep-training/exit / next-question）：返回完整事件流文本。
     */
    private String ssePost(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private JsonNode post(String path, String token, Map<String, Object> body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private JsonNode get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private void assertCode(JsonNode response, int expected) {
        assertThat(response.at("/code").asInt()).as("response: %s", response).isEqualTo(expected);
    }

    /** 从 SSE 响应体中提取 done 事件载荷 JSON */
    private JsonNode doneOf(String sseBody) throws Exception {
        String[] lines = sseBody.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].startsWith("event:done") && lines[i + 1].startsWith("data:")) {
                return objectMapper.readTree(lines[i + 1].substring("data:".length()).trim());
            }
        }
        throw new AssertionError("SSE 响应缺少 done 事件：" + sseBody);
    }
}
