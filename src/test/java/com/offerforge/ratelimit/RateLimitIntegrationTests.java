package com.offerforge.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.email.EmailVerificationCodeStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流端到端测试（内存滑动窗口实现，本类单独收紧限额验证超限行为）。
 * /api/qa/ask 每用户每分钟 5 次、/api/report/{id} 详情每分钟 3 次，超限返回 HTTP 429 + 42900；
 * 历史列表/进步曲线不限流（页面加载必需）；
 * SSE 端点（面试作答）超限以 event:error 事件流返回 42900；
 * 同一用户同时只允许一场进行中的面试。
 */
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "offerforge.rate-limit.interview-ask-limit=10",
        "offerforge.rate-limit.qa-ask-limit=5",
        "offerforge.rate-limit.report-limit=3",
        "offerforge.rate-limit.session-lifecycle-limit=4",
        // 独立内存库：本类需要单独上下文收紧限额，避免 create-drop 重置共享库自增 ID 污染其他测试
        "spring.datasource.url=jdbc:h2:mem:offerforge_ratelimit;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=COMMENT;DB_CLOSE_DELAY=-1"
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private EmailVerificationCodeStore codeStore;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void qaAskExceedsPerMinuteLimitReturns429() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);

        // 窗口内前 5 次放行
        for (int i = 0; i < 5; i++) {
            assertCode(post("/api/qa/ask", token, Map.of("question", "什么是 JVM 垃圾回收？")), 0);
        }

        // 第 6 次触发限流：HTTP 429 + 业务码 42900
        HttpResponse<String> blocked = rawPost("/api/qa/ask", token, Map.of("question", "再问一个"));
        assertThat(blocked.statusCode()).isEqualTo(429);
        JsonNode body = objectMapper.readTree(blocked.body());
        assertThat(body.at("/code").asInt()).isEqualTo(42900);
        assertThat(body.at("/message").asText()).contains("请求过于频繁");
    }

    @Test
    void reportDetailExceedsPerMinuteLimitReturns429() throws Exception {
        String token = newUser();

        // 窗口内前 3 次过限流（报告不存在返回业务 40400，不影响限流计数验证）
        for (int i = 0; i < 3; i++) {
            assertCode(get("/api/report/nonexistent-session", token), 40400);
        }

        // 第 4 次触发限流
        HttpResponse<String> blocked = rawGet("/api/report/nonexistent-session", token);
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(objectMapper.readTree(blocked.body()).at("/code").asInt()).isEqualTo(42900);
    }

    @Test
    void historyAndProgressAreNotRateLimited() throws Exception {
        String token = newUser();

        // 页面加载必需的列表/趋势接口：连续请求远超原限额也不应被限流
        for (int i = 0; i < 10; i++) {
            assertCode(get("/api/report/history", token), 0);
            assertCode(get("/api/report/progress", token), 0);
        }
    }

    @Test
    void interviewAskExceedsPerMinuteLimitReturns429EventStream() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        String sessionId = post("/api/interview/start", token, Map.of()).at("/data/sessionId").asText();

        // 空白作答不推进面试但计入限流窗口，窗口内前 10 次放行
        for (int i = 0; i < 10; i++) {
            assertThat(askRaw(sessionId, token, " ")).contains("event:error").contains("40000");
        }

        // 第 11 次：HTTP 429 + event:error 事件（SSE 端点不返回 JSON，避免内容协商冲突）
        HttpResponse<String> blocked = askResponse(sessionId, token, " ");
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.body()).contains("event:error").contains("42900").contains("请求过于频繁");
    }

    @Test
    void sessionLifecycleExceedsPerMinuteLimitReturns429() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);

        // 开局/结束共用限额，窗口内前 4 次（2 轮开局+结束）放行；限制脚本循环短场套取额度退还/刷官方模型开场调用
        for (int i = 0; i < 2; i++) {
            String sessionId = post("/api/interview/start", token, Map.of()).at("/data/sessionId").asText();
            assertCode(post("/api/interview/" + sessionId + "/finish", token, Map.of()), 0);
        }

        // 第 5 次开局触发限流：HTTP 429 + 业务码 42900
        HttpResponse<String> blocked = rawPost("/api/interview/start", token, Map.of());
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(objectMapper.readTree(blocked.body()).at("/code").asInt()).isEqualTo(42900);
    }

    @Test
    void onlyOneActiveInterviewAllowedPerUser() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);

        JsonNode first = post("/api/interview/start", token, Map.of());
        assertCode(first, 0);
        String sessionId = first.at("/data/sessionId").asText();

        // 已有进行中的面试 → 拒绝开始第二场
        JsonNode second = post("/api/interview/start", token, Map.of());
        assertCode(second, 40900);
        assertThat(second.at("/message").asText()).contains("已有一场面试");

        // 前一场结束后恢复可开始
        assertCode(post("/api/interview/" + sessionId + "/finish", token, Map.of()), 0);
        assertCode(post("/api/interview/start", token, Map.of()), 0);
    }

    private String newUser() throws Exception {
        String username = "ratelimit_user_" + System.nanoTime();
        String email = username.toLowerCase() + "@test.local";
        codeStore.saveCode(email, "135790");
        assertCode(post("/api/auth/register", null,
                Map.of("email", email, "code", "135790", "username", username, "password", "123456")), 0);
        JsonNode login = post("/api/auth/login", null, Map.of("username", username, "password", "123456"));
        assertCode(login, 0);
        return login.at("/data/token").asText();
    }

    private String askRaw(String sessionId, String token, String message) throws Exception {
        return askResponse(sessionId, token, message).body();
    }

    private HttpResponse<String> askResponse(String sessionId, String token, String message) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/interview/" + sessionId + "/ask"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of("message", message)), StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode post(String path, String token, Map<String, Object> body) throws Exception {
        HttpResponse<String> response = rawPost(path, token, body);
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> rawPost(String path, String token, Map<String, Object> body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path, String token) throws Exception {
        return objectMapper.readTree(rawGet(path, token).body());
    }

    private HttpResponse<String> rawGet(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertCode(JsonNode response, int expected) {
        assertThat(response.at("/code").asInt()).as("response: %s", response).isEqualTo(expected);
    }
}
