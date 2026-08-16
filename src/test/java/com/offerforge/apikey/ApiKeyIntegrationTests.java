package com.offerforge.apikey;

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
 * API Key 管理端到端：保存 → 状态查询（只回 provider，不含明文）→ 面试以用户 Key 模式开始 → 删除；
 * 以及鉴权与参数校验分支。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKeyIntegrationTests {

    private static final String PLAIN_KEY = "sk-integration-test-secret-key";

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void saveQueryUseAndDeleteKey() throws Exception {
        String token = newUser();

        // 初始未配置
        JsonNode status0 = get("/api/apikey", token);
        assertCode(status0, 0);
        assertThat(status0.at("/data/configured").asBoolean()).isFalse();

        // 保存千问 Key（model 缺省）
        JsonNode saved = post("/api/apikey", token,
                Map.of("provider", "QIANWEN", "apiKey", PLAIN_KEY));
        assertCode(saved, 0);
        assertThat(saved.at("/data/configured").asBoolean()).isTrue();
        assertThat(saved.at("/data/provider").asText()).isEqualTo("QIANWEN");

        // 状态查询只回 provider，且任何字段都不含明文 Key
        HttpResponse<String> statusRaw = getRaw("/api/apikey", token);
        JsonNode status1 = objectMapper.readTree(statusRaw.body());
        assertCode(status1, 0);
        assertThat(status1.at("/data/configured").asBoolean()).isTrue();
        assertThat(status1.at("/data/provider").asText()).isEqualTo("QIANWEN");
        assertThat(statusRaw.body()).doesNotContain(PLAIN_KEY);

        // 有自带 Key 时面试直接开始（用户 Key 模式，不消耗额度）
        JsonNode start = post("/api/interview/start", token, Map.of());
        assertCode(start, 0);
        String sessionId = start.at("/data/sessionId").asText();
        assertCode(post("/api/interview/" + sessionId + "/finish", token, Map.of()), 0);

        // 删除后恢复未配置状态
        assertCode(delete("/api/apikey", token), 0);
        JsonNode status2 = get("/api/apikey", token);
        assertThat(status2.at("/data/configured").asBoolean()).isFalse();
        assertThat(status2.at("/data/provider").isNull()).isTrue();
    }

    @Test
    void saveOverwritesExistingKey() throws Exception {
        String token = newUser();

        assertCode(post("/api/apikey", token, Map.of("provider", "QIANWEN", "apiKey", PLAIN_KEY)), 0);
        // 更新为 OpenAI 兼容（同用户单条，覆盖 provider）
        assertCode(post("/api/apikey", token, Map.of(
                "provider", "OPENAI_COMPATIBLE",
                "baseUrl", "https://203.0.113.10/v1",
                "model", "gpt-4o-mini",
                "apiKey", PLAIN_KEY)), 0);

        HttpResponse<String> statusRaw = getRaw("/api/apikey", token);
        JsonNode status = objectMapper.readTree(statusRaw.body());
        assertThat(status.at("/data/provider").asText()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(statusRaw.body()).doesNotContain(PLAIN_KEY);
    }

    @Test
    void validationAndAuthBranches() throws Exception {
        String token = newUser();

        // 未登录
        assertCode(post("/api/apikey", null, Map.of("provider", "QIANWEN", "apiKey", PLAIN_KEY)), 40100);
        assertCode(get("/api/apikey", null), 40100);
        assertCode(delete("/api/apikey", null), 40100);

        // 非法 provider
        assertCode(post("/api/apikey", token, Map.of("provider", "UNKNOWN", "apiKey", PLAIN_KEY)), 40000);
        // key 为空
        assertCode(post("/api/apikey", token, Map.of("provider", "QIANWEN", "apiKey", "")), 40000);
        // OpenAI 兼容缺 baseUrl / 缺 model
        assertCode(post("/api/apikey", token, Map.of("provider", "OPENAI_COMPATIBLE", "model", "m", "apiKey", PLAIN_KEY)), 40000);
        assertCode(post("/api/apikey", token, Map.of("provider", "OPENAI_COMPATIBLE", "baseUrl", "https://203.0.113.10/v1", "apiKey", PLAIN_KEY)), 40000);
        // baseUrl 非 https / 指向内网地址拒绝（防 SSRF）
        assertCode(post("/api/apikey", token, Map.of("provider", "OPENAI_COMPATIBLE", "baseUrl", "http://203.0.113.10/v1", "model", "m", "apiKey", PLAIN_KEY)), 40000);
        assertCode(post("/api/apikey", token, Map.of("provider", "OPENAI_COMPATIBLE", "baseUrl", "https://127.0.0.1/v1", "model", "m", "apiKey", PLAIN_KEY)), 40000);
    }

    private String newUser() throws Exception {
        String username = "apikey_user_" + System.nanoTime();
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
        return objectMapper.readTree(getRaw(path, token).body());
    }

    private HttpResponse<String> getRaw(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
