package com.offerforge.quota;

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
 * 免费额度端到端（daily-limit=2）：
 * 无 Key 用完额度 → 第 3 场被拒（429 QUOTA_EXCEEDED）→ 配置自带 Key → 无限制；删除 Key 后恢复额度约束。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "offerforge.quota.enabled=true",
        "offerforge.quota.daily-limit=2"
})
class QuotaIntegrationTests {

    @LocalServerPort
    private int port;

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

        // 第 1、2 场（边界）正常开始，结束后释放会话
        startAndFinish(token);
        startAndFinish(token);

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

    private void startAndFinish(String token) throws Exception {
        JsonNode start = post("/api/interview/start", token, Map.of());
        assertCode(start, 0);
        String sessionId = start.at("/data/sessionId").asText();
        assertCode(post("/api/interview/" + sessionId + "/finish", token, Map.of()), 0);
    }

    private String newUser() throws Exception {
        String username = "quota_user_" + System.nanoTime();
        assertCode(post("/api/auth/register", null, Map.of("username", username, "password", "123456")), 0);
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
