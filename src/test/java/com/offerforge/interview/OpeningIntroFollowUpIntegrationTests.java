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
 * 开场自我介绍主动追问端到端：信息不全时面试官主动索取缺失信息（最多 2 次），
 * 信息充分或追问达上限后照常推进到基础考察。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpeningIntroFollowUpIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private EmailVerificationCodeStore codeStore;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void insufficientIntroTriggersFollowUpThenAdvances() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        String sessionId = post("/api/interview/start", token, Map.of()).at("/data/sessionId").asText();

        // 第 1 次：自我介绍信息不全 → 主动补充提问，仍停留开场环节
        String first = ask(sessionId, token, "信息不全");
        assertThat(first).contains("event:progress").contains("正在整理你的自我介绍")
                .contains("你的自我介绍信息还不够")
                .contains("\"action\":\"FOLLOW_UP\"").contains("\"state\":\"OPENING\"");

        // 第 2 次：仍旧不全 → 再次追问（上限 2 次）
        assertThat(ask(sessionId, token, "信息不全"))
                .contains("你的自我介绍信息还不够")
                .contains("\"action\":\"FOLLOW_UP\"").contains("\"state\":\"OPENING\"");

        // 第 3 次：追问已达上限 → 不再追问，直接推进基础考察
        assertThat(ask(sessionId, token, "信息不全"))
                .contains("\"action\":\"ADVANCE\"").contains("\"state\":\"BASICS\"");
    }

    @Test
    void sufficientIntroAdvancesDirectly() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        String sessionId = post("/api/interview/start", token, Map.of()).at("/data/sessionId").asText();

        assertThat(ask(sessionId, token, "我熟悉 Java 后端开发，做过电商项目。"))
                .contains("\"action\":\"ADVANCE\"").contains("\"state\":\"BASICS\"");
    }

    private String newUser() throws Exception {
        String username = "intro_user_" + System.nanoTime();
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
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
