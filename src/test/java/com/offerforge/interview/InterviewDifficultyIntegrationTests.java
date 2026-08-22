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
 * 难度控制端到端测试：由浅入深从简单起步，连续高分推进阶段，DEEP 阶段连续高分触发难度提升并留阶段换题。
 * 题量上限：BASICS=2（验证高分但连击不足时仍推进）、PROJECT=1、DEEP=3。
 */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "offerforge.interview.max-basics-questions=2",
                "offerforge.interview.max-project-questions=1",
                "offerforge.interview.max-deep-questions=3"
        })
class InterviewDifficultyIntegrationTests {

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
    void consecutiveHighScoresRaiseDifficultyThenAdvance() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        JsonNode start = post("/api/interview/start", token, Map.of());
        String sessionId = start.at("/data/sessionId").asText();
        // 由浅入深：初始难度为简单
        assertThat(start.at("/data/status/difficultyLabel").asText()).isEqualTo("简单");

        // 自我介绍 → BASICS
        ask(sessionId, token, "自我介绍：熟悉 Java 后端。");

        // BASICS 第 1 题高分：连击=1 不足 2 → 推进 PROJECT（即使 BASICS 上限=2）；实战模式 done 不返回分数
        String sse1 = ask(sessionId, token, LONG_ANSWER);
        assertThat(sse1).contains("\"action\":\"ADVANCE\"").contains("\"state\":\"PROJECT\"");

        // PROJECT 高分：连击=2 但阶段上限=1 已达 → 推进 DEEP
        String sse2 = ask(sessionId, token, LONG_ANSWER);
        assertThat(sse2).contains("\"action\":\"ADVANCE\"").contains("\"state\":\"DEEP\"");
        JsonNode statusBeforeRaise = get("/api/interview/" + sessionId + "/status", token);
        assertThat(statusBeforeRaise.at("/data/difficultyLabel").asText()).isEqualTo("简单");

        // DEEP 第 1 题高分：连击=3 且难度可提升 → 留阶段换更高难度题（EASY→MEDIUM）
        String sse3 = ask(sessionId, token, LONG_ANSWER);
        assertThat(sse3).contains("\"action\":\"NEW_QUESTION\"").contains("\"state\":\"DEEP\"");
        JsonNode statusAfterRaise = get("/api/interview/" + sessionId + "/status", token);
        assertThat(statusAfterRaise.at("/data/difficultyLabel").asText()).isEqualTo("中等");
        assertThat(statusAfterRaise.at("/data/currentQuestion").asText()).isNotBlank();

        // DEEP 第 2 题（MEDIUM）高分：升档后连击重置为 1 → 推进 CLOSING
        String sse4 = ask(sessionId, token, LONG_ANSWER);
        assertThat(sse4).contains("\"action\":\"ADVANCE\"").contains("\"state\":\"CLOSING\"").contains("考察环节已结束");

        // 收尾后结束会话；主问题共 4 题（BASICS 1 + PROJECT 1 + DEEP 2），平均 8 分 → 综合分 80
        ask(sessionId, token, "谢谢面试官。");
        JsonNode finish = post("/api/interview/" + sessionId + "/finish", token, Map.of());
        assertCode(finish, 0);
        assertThat(finish.at("/data/archived").asBoolean()).isTrue();
        assertThat(finish.at("/data/report/totalQuestions").asInt()).isEqualTo(4);
        assertThat(finish.at("/data/report/overallScore").asDouble()).isEqualTo(80.0);
    }

    private String newUser() throws Exception {
        String username = "difficulty_user_" + System.nanoTime();
        String email = username.toLowerCase() + "@test.local";
        codeStore.saveCode(email, "135790");
        assertCode(post("/api/auth/register", null,
                Map.of("email", email, "code", "135790", "username", username, "password", "123456")), 0);
        JsonNode login = post("/api/auth/login", null, Map.of("username", username, "password", "123456"));
        assertCode(login, 0);
        return login.at("/data/token").asText();
    }

    private String ask(String sessionId, String token, String message) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/interview/" + sessionId + "/ask"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of("message", message)), StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private void assertCode(JsonNode response, int expected) {
        assertThat(response.at("/code").asInt()).as("response: %s", response).isEqualTo(expected);
    }
}
