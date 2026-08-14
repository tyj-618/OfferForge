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
 * 跳过此题端到端测试（test profile：各阶段题量上限=1，跳过即推进阶段）。
 * 跳过计 0 分并纳入平均分；开场/收尾环节无题可跳，返回 40900。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SkipQuestionIntegrationTests {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void skipRecordsZeroScoreAndAdvancesThroughPhases() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        String sessionId = post("/api/interview/start", token, Map.of()).at("/data/sessionId").asText();

        // 开场环节（自我介绍）无题可跳 → SSE error 40900
        assertThat(skip(sessionId, token)).contains("event:error").contains("40900");

        // 自我介绍作答 → 进入基础考察
        assertThat(ask(sessionId, token, "我熟悉 Java 后端开发，做过电商项目。"))
                .contains("\"action\":\"ADVANCE\"").contains("\"state\":\"BASICS\"");

        // 逐题跳过：计 0 分并逐阶段推进（各阶段 1 题 → 跳过即 ADVANCE）
        String skipBasics = skip(sessionId, token);
        assertThat(skipBasics).contains("event:done").contains("已跳过该题")
                .contains("\"action\":\"ADVANCE\"").contains("\"state\":\"PROJECT\"");
        assertThat(skip(sessionId, token))
                .contains("\"action\":\"ADVANCE\"").contains("\"state\":\"DEEP\"");
        String skipDeep = skip(sessionId, token);
        assertThat(skipDeep).contains("\"action\":\"ADVANCE\"")
                .contains("\"state\":\"CLOSING\"").contains("考察环节已结束");

        // 收尾环节无题可跳 → SSE error 40900
        assertThat(skip(sessionId, token)).contains("event:error").contains("40900");

        // 结束面试：3 题全部跳过 → 各题 0 分，综合分 0
        JsonNode finish = post("/api/interview/" + sessionId + "/finish", token, Map.of());
        assertCode(finish, 0);
        assertThat(finish.at("/data/totalQuestions").asInt()).isEqualTo(3);
        assertThat(finish.at("/data/overallScore").asDouble()).isZero();
        JsonNode evaluations = finish.at("/data/questionEvaluations");
        assertThat(evaluations.size()).isEqualTo(3);
        for (JsonNode evaluation : evaluations) {
            assertThat(evaluation.at("/score").asDouble()).isZero();
        }
    }

    private String newUser() throws Exception {
        String username = "skip_user_" + System.nanoTime();
        assertCode(post("/api/auth/register", null, Map.of("username", username, "password", "123456")), 0);
        JsonNode login = post("/api/auth/login", null, Map.of("username", username, "password", "123456"));
        assertCode(login, 0);
        return login.at("/data/token").asText();
    }

    /** 跳过当前题（SSE，无请求体），返回完整事件流文本 */
    private String skip(String sessionId, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/interview/" + sessionId + "/skip"))
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private String ask(String sessionId, String token, String message) throws Exception {
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

    private void assertCode(JsonNode response, int expected) {
        assertThat(response.at("/code").asInt()).as("response: %s", response).isEqualTo(expected);
    }
}
