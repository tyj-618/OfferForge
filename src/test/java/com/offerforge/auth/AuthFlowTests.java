package com.offerforge.auth;

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

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowTests {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registerLoginLogoutFlow() throws Exception {
        String username = "auth_user_" + System.nanoTime();
        JsonNode registerResult = post("/api/auth/register", Map.of("username", username, "password", "123456"));
        assertCode(registerResult, 0);
        assertThat(registerResult.at("/data/username").asText()).isEqualTo(username);
        assertThat(registerResult.at("/data/nickname").asText()).startsWith("Candidate_");

        JsonNode loginResult = post("/api/auth/login", Map.of("username", username, "password", "123456"));
        assertCode(loginResult, 0);
        String token = loginResult.at("/data/token").asText();
        assertThat(token).isNotBlank();
        assertThat(loginResult.at("/data/refreshToken").asText()).isNotBlank();

        JsonNode logoutResult = post("/api/auth/logout", Map.of(), "Bearer " + token);
        assertCode(logoutResult, 0);
    }

    @Test
    void duplicateUsernameIsRejected() throws Exception {
        String username = "dup_user_" + System.nanoTime();
        assertCode(post("/api/auth/register", Map.of("username", username, "password", "123456")), 0);
        JsonNode duplicate = post("/api/auth/register", Map.of("username", username, "password", "654321"));
        assertCode(duplicate, 40901);
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        String username = "pwd_user_" + System.nanoTime();
        assertCode(post("/api/auth/register", Map.of("username", username, "password", "123456")), 0);
        JsonNode loginResult = post("/api/auth/login", Map.of("username", username, "password", "wrong-pass"));
        assertCode(loginResult, 40001);
    }

    @Test
    void logoutWithoutAnyCredentialIsUnauthorized() throws Exception {
        JsonNode result = post("/api/auth/logout", Map.of());
        assertCode(result, 40100);
    }

    @Test
    void invalidRegisterPayloadReturnsParamError() throws Exception {
        JsonNode result = post("/api/auth/register", Map.of("username", "ab", "password", "123"));
        assertCode(result, 40000);
    }

    @Test
    void meReturnsCurrentUserSummary() throws Exception {
        String username = "me_user_" + System.nanoTime();
        assertCode(post("/api/auth/register", Map.of("username", username, "password", "123456")), 0);
        JsonNode login = post("/api/auth/login", Map.of("username", username, "password", "123456"));
        assertCode(login, 0);
        String token = login.at("/data/token").asText();
        // 登录响应本身携带用户摘要（前端登录时缓存）
        assertThat(login.at("/data/user/username").asText()).isEqualTo(username);

        // /api/auth/me：刷新恢复场景（token 在而登录缓存丢失）补齐用户名
        JsonNode me = get("/api/auth/me", "Bearer " + token);
        assertCode(me, 0);
        assertThat(me.at("/data/username").asText()).isEqualTo(username);
        assertThat(me.at("/data/nickname").asText()).startsWith("Candidate_");

        // 未登录访问 → 40100
        assertCode(get("/api/auth/me", null), 40100);
    }

    private JsonNode post(String path, Map<String, Object> body) throws Exception {
        return post(path, body, null);
    }

    private JsonNode post(String path, Map<String, Object> body, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private JsonNode get(String path, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private void assertCode(JsonNode response, int expected) {
        assertThat(response.at("/code").asInt()).as("response: %s", response).isEqualTo(expected);
    }
}
