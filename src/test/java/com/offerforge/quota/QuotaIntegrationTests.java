package com.offerforge.quota;

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
 * 免费额度端到端（daily-limit=2，各阶段题量 2，共 6 题）：
 * 完整场次（问答≥5 题）消耗额度，用完后第 3 场被拒（429 QUOTA_EXCEEDED）→ 配置自带 Key → 无限制；
 * 短场（问答不足 5 题）结束退还开局扣减，不消耗额度，且不记录历史。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "offerforge.quota.enabled=true",
        "offerforge.quota.daily-limit=2",
        // 恢复生产计次门槛 5（test profile 降为 1）：短场退还与短场不记录历史的语义依赖该门槛
        "offerforge.quota.min-billable-questions=5",
        // 每阶段 2 题共 6 题：保证完整场次可达 5 题计次门槛（存量 test profile 共 3 题永不足门槛）
        "offerforge.interview.max-basics-questions=2",
        "offerforge.interview.max-project-questions=2",
        "offerforge.interview.max-deep-questions=2"
})
class QuotaIntegrationTests {

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
    void quotaExhaustionBlocksStartUntilUserKeyConfigured() throws Exception {
        String token = newUser();

        // 初始状态：无 Key，剩余额度 2
        JsonNode quota0 = get("/api/quota", token);
        assertCode(quota0, 0);
        assertThat(quota0.at("/data/hasOwnKey").asBoolean()).isFalse();
        assertThat(quota0.at("/data/remaining").asInt()).isEqualTo(2);
        assertThat(quota0.at("/data/dailyLimit").asInt()).isEqualTo(2);
        assertThat(quota0.at("/data/enabled").asBoolean()).isTrue();

        // 第 1、2 场（边界）完整作答 6 题（≥5 题计次门槛）正常消耗额度，结束后释放会话
        completeFullInterview(token);
        completeFullInterview(token);

        JsonNode quota1 = get("/api/quota", token);
        assertThat(quota1.at("/data/remaining").asInt()).isZero();

        // 第 3 场被拒：HTTP 429 + 字符串业务码契约
        HttpResponse<String> rejected = postRaw("/api/interview/start", token, Map.of());
        assertThat(rejected.statusCode()).isEqualTo(429);
        JsonNode rejectedBody = objectMapper.readTree(rejected.body());
        assertThat(rejectedBody.at("/code").asText()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(rejectedBody.at("/message").asText()).isEqualTo("今日免费额度已用完");
        assertThat(rejectedBody.at("/remainingQuota").asInt()).isZero();

        // 配置自带 Key 后立即可开始（不消耗额度）
        assertCode(post("/api/apikey", token, Map.of("provider", "QIANWEN", "apiKey", "sk-user-own-key")), 0);
        JsonNode quota2 = get("/api/quota", token);
        assertThat(quota2.at("/data/hasOwnKey").asBoolean()).isTrue();
        startAndFinish(token);

        // 删除 Key 后恢复额度约束：当日额度已耗尽，继续被拒
        assertCode(delete("/api/apikey", token), 0);
        JsonNode quota3 = get("/api/quota", token);
        assertThat(quota3.at("/data/hasOwnKey").asBoolean()).isFalse();
        assertThat(quota3.at("/data/remaining").asInt()).isZero();
        assertThat(postRaw("/api/interview/start", token, Map.of()).statusCode()).isEqualTo(429);
    }

    @Test
    void quotaEndpointRequiresLogin() throws Exception {
        assertCode(get("/api/quota", null), 40100);
    }

    @Test
    void shortSessionUnderFiveQuestionsDoesNotConsumeQuota() throws Exception {
        String token = newUser();

        // 开局即扣 1 次；零作答直接结束触发短场退还，额度回满（防误触消耗）；
        // 短场同时不记录历史：finish 返回 archived=false、report=null
        JsonNode start = post("/api/interview/start", token, Map.of());
        assertCode(start, 0);
        String sessionId = start.at("/data/sessionId").asText();
        assertThat(get("/api/quota", token).at("/data/remaining").asInt()).isEqualTo(1);
        JsonNode shortFinish = post("/api/interview/" + sessionId + "/finish", token, Map.of());
        assertCode(shortFinish, 0);
        assertThat(shortFinish.at("/data/archived").asBoolean()).isFalse();
        assertThat(shortFinish.at("/data/report").isNull()).isTrue();
        assertThat(get("/api/quota", token).at("/data/remaining").asInt()).isEqualTo(2);

        // 作答不足 5 题（仅 2 题）提前结束：同样退还，重复短场不消耗额度
        JsonNode start2 = post("/api/interview/start", token, Map.of());
        assertCode(start2, 0);
        String session2 = start2.at("/data/sessionId").asText();
        ask(session2, token, "我熟悉 Java 后端开发，做过电商项目。");
        ask(session2, token, LONG_ANSWER);
        ask(session2, token, LONG_ANSWER);
        assertCode(post("/api/interview/" + session2 + "/finish", token, Map.of()), 0);
        assertThat(get("/api/quota", token).at("/data/remaining").asInt()).isEqualTo(2);

        // 两场短场均未归档：历史为空，报告查询 40400（不存在的会话）
        assertThat(get("/api/report/history?page=0&size=10", token)
                .at("/data/totalElements").asInt()).isZero();
        assertCode(get("/api/report/" + sessionId, token), 40400);
    }

    /** 完整场次：开场自我介绍 + 逐题作答直到 CLOSING（6 题 ≥ 5 题计次门槛）后结束，真实消耗额度 */
    private void completeFullInterview(String token) throws Exception {
        // 导入知识库：BASICS/DEEP 官方题库依赖可见条目，否则整阶段跳过导致题数不足门槛
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        JsonNode start = post("/api/interview/start", token, Map.of());
        assertCode(start, 0);
        String sessionId = start.at("/data/sessionId").asText();
        ask(sessionId, token, "我熟悉 Java 后端开发，做过电商项目。");
        for (int round = 0; round < 12; round++) {
            String state = get("/api/interview/" + sessionId + "/status", token).at("/data/state").asText();
            if ("CLOSING".equals(state) || "FINISHED".equals(state)) {
                break;
            }
            ask(sessionId, token, LONG_ANSWER);
        }
        assertCode(post("/api/interview/" + sessionId + "/finish", token, Map.of()), 0);
    }

    /** SSE 问答：驱动面试回合推进 */
    private void ask(String sessionId, String token, String message) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/interview/" + sessionId + "/ask"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of("message", message)), StandardCharsets.UTF_8))
                .header("Authorization", "Bearer " + token);
        httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void startAndFinish(String token) throws Exception {
        JsonNode start = post("/api/interview/start", token, Map.of());
        assertCode(start, 0);
        String sessionId = start.at("/data/sessionId").asText();
        assertCode(post("/api/interview/" + sessionId + "/finish", token, Map.of()), 0);
    }

    private String newUser() throws Exception {
        String username = "quota_user_" + System.nanoTime();
        String email = username.toLowerCase() + "@test.local";
        codeStore.saveCode(email, "135790");
        assertCode(post("/api/auth/register", null,
                Map.of("email", email, "code", "135790", "username", username, "password", "123456")), 0);
        JsonNode login = post("/api/auth/login", null, Map.of("username", username, "password", "123456"));
        assertCode(login, 0);
        return login.at("/data/token").asText();
    }

    private JsonNode post(String path, String token, Map<String, Object> body) throws Exception {
        return objectMapper.readTree(postRaw(path, token, body).body());
    }

    private HttpResponse<String> postRaw(String path, String token, Map<String, Object> body) throws Exception {
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
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private JsonNode delete(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .DELETE();
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
