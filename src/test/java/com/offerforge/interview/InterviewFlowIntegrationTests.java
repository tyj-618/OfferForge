package com.offerforge.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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

        // 第 2 轮：长回答评分 8 → 推进 PROJECT
        String sse2 = ask(sessionId, token, LONG_ANSWER);
        assertThat(sse2).contains("\"score\":8").contains("\"action\":\"ADVANCE\"").contains("\"state\":\"PROJECT\"");

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

        // 第 1 次低分 → 追问（同知识点换角度）
        String sse1 = ask(sessionId, token, "嗯。");
        assertThat(sse1).contains("\"score\":3").contains("\"action\":\"FOLLOW_UP\"").contains("模拟追问");
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

        // 第 3 次低分：追问已用尽且阶段题量达上限 → 推进 PROJECT
        String sse3 = ask(sessionId, token, "不会。");
        assertThat(sse3).contains("\"action\":\"ADVANCE\"").contains("\"state\":\"PROJECT\"");
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

    private String newUser() throws Exception {
        String username = "interview_user_" + System.nanoTime();
        assertCode(post("/api/auth/register", null, Map.of("username", username, "password", "123456")), 0);
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
}
