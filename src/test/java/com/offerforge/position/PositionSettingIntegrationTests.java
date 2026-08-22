package com.offerforge.position;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 面试岗位设置端到端：保存当前岗位与自定义岗位 → 重新 GET 持久化可见 →
 * 更改岗位覆盖 → 用户隔离 → 鉴权与参数校验分支。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PositionSettingIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private EmailVerificationCodeStore codeStore;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void saveAndReadBackPositionSetting() throws Exception {
        String token = newUser();

        // 初始无设置
        JsonNode empty = get("/api/interview/position-setting", token);
        assertCode(empty, 0);
        assertThat(empty.at("/data/currentPosition").isNull()).isTrue();
        assertThat(empty.at("/data/customPositions").isArray()).isTrue();
        assertThat(empty.at("/data/customPositions").size()).isZero();

        // 保存：预设岗位 + 一个自定义岗位（绑定官方与自定义标签）
        JsonNode saved = put("/api/interview/position-setting", token, Map.of(
                "currentPosition", "Java 后端工程师",
                "customPositions", List.of(Map.of(
                        "name", "大数据开发",
                        "tags", List.of("Java基础", "MySQL", "自研中间件")))));
        assertCode(saved, 0);
        assertThat(saved.at("/data/currentPosition").asText()).isEqualTo("Java 后端工程师");
        assertThat(saved.at("/data/customPositions/0/name").asText()).isEqualTo("大数据开发");
        assertThat(saved.at("/data/customPositions/0/tags/2").asText()).isEqualTo("自研中间件");

        // 重新 GET：设置持久保留
        JsonNode reloaded = get("/api/interview/position-setting", token);
        assertCode(reloaded, 0);
        assertThat(reloaded.at("/data/currentPosition").asText()).isEqualTo("Java 后端工程师");
        assertThat(reloaded.at("/data/customPositions/0/name").asText()).isEqualTo("大数据开发");

        // 更改岗位：覆盖 currentPosition，自定义岗位同步更新
        JsonNode updated = put("/api/interview/position-setting", token, Map.of(
                "currentPosition", "大数据开发",
                "customPositions", List.of(Map.of("name", "大数据开发", "tags", List.of("MySQL")))));
        assertCode(updated, 0);
        assertThat(updated.at("/data/currentPosition").asText()).isEqualTo("大数据开发");
        assertThat(updated.at("/data/customPositions").size()).isOne();
        assertThat(updated.at("/data/customPositions/0/tags/0").asText()).isEqualTo("MySQL");
    }

    @Test
    void clearCurrentPositionAndCustomPositions() throws Exception {
        String token = newUser();
        assertCode(put("/api/interview/position-setting", token, Map.of(
                "currentPosition", "Java 后端工程师",
                "customPositions", List.of(Map.of("name", "自定义岗", "tags", List.of("Redis"))))), 0);

        // 清空：currentPosition 空串归一为 null
        JsonNode cleared = put("/api/interview/position-setting", token, Map.of(
                "currentPosition", "  ",
                "customPositions", List.of()));
        assertCode(cleared, 0);
        assertThat(cleared.at("/data/currentPosition").isNull()).isTrue();
        assertThat(cleared.at("/data/customPositions").size()).isZero();
    }

    @Test
    void settingsAreIsolatedBetweenUsers() throws Exception {
        String tokenA = newUser();
        String tokenB = newUser();

        assertCode(put("/api/interview/position-setting", tokenA, Map.of(
                "currentPosition", "Java 后端工程师",
                "customPositions", List.of())), 0);

        JsonNode other = get("/api/interview/position-setting", tokenB);
        assertCode(other, 0);
        assertThat(other.at("/data/currentPosition").isNull()).isTrue();
    }

    @Test
    void rejectsUnauthorizedAndInvalidPayload() throws Exception {
        String token = newUser();

        // 未登录
        assertCode(get("/api/interview/position-setting", null), 40100);
        assertCode(put("/api/interview/position-setting", null, Map.of("currentPosition", "X")), 40100);

        // 岗位名超长
        assertCode(put("/api/interview/position-setting", token,
                Map.of("currentPosition", "岗".repeat(65))), 40000);
        // 自定义岗位名为空
        assertCode(put("/api/interview/position-setting", token,
                Map.of("customPositions", List.of(Map.of("name", " ", "tags", List.of())))), 40000);
        // 自定义岗位名重复
        assertCode(put("/api/interview/position-setting", token, Map.of(
                "customPositions", List.of(
                        Map.of("name", "同岗", "tags", List.of()),
                        Map.of("name", "同岗", "tags", List.of())))), 40000);
        // 标签超长
        assertCode(put("/api/interview/position-setting", token, Map.of(
                "customPositions", List.of(Map.of("name", "岗", "tags", List.of("标".repeat(33)))))), 40000);
    }

    private String newUser() throws Exception {
        String username = "position_user_" + System.nanoTime();
        String email = username + "@test.local";
        codeStore.saveCode(email, "135790");
        assertCode(post("/api/auth/register",
                Map.of("email", email, "code", "135790", "username", username, "password", "123456")), 0);
        JsonNode login = post("/api/auth/login", Map.of("username", username, "password", "123456"));
        assertCode(login, 0);
        return login.at("/data/token").asText();
    }

    private JsonNode post(String path, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        return objectMapper.readTree(httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }

    private JsonNode put(String path, String token, Map<String, Object> body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return objectMapper.readTree(httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    private JsonNode get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return objectMapper.readTree(httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    private void assertCode(JsonNode response, int expected) {
        assertThat(response.at("/code").asInt()).as("response: %s", response).isEqualTo(expected);
    }
}
