package com.offerforge.admin;

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
 * 管理台集成测试（test profile，管理员用户名 admin_tester 见 application-test.yaml）：
 * whoami 身份认定 → 非管理员 40300 → 统计/列表/检索 → 封禁后登录拒绝 → 解封恢复 → 管理员不可被封。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminConsoleIntegrationTests {

    private static final String ADMIN_USERNAME = "admin_tester";
    private static final String ADMIN_PASSWORD = "admin_pass_1";

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void whoamiReflectsAdminRole() throws Exception {
        String adminToken = registerAndLogin(ADMIN_USERNAME, ADMIN_PASSWORD);
        String suffix = String.valueOf(System.nanoTime());
        String normalToken = registerAndLogin("normal_" + suffix, "pass_" + suffix);

        JsonNode adminWhoami = get("/api/admin/whoami", "Bearer " + adminToken);
        assertCode(adminWhoami, 0);
        assertThat(adminWhoami.at("/data/admin").asBoolean()).isTrue();

        JsonNode normalWhoami = get("/api/admin/whoami", "Bearer " + normalToken);
        assertCode(normalWhoami, 0);
        assertThat(normalWhoami.at("/data/admin").asBoolean()).isFalse();

        // 未登录 → 40100
        assertCode(get("/api/admin/whoami", null), 40100);
    }

    @Test
    void statsAndUsersAreAdminOnly() throws Exception {
        String adminToken = registerAndLogin(ADMIN_USERNAME, ADMIN_PASSWORD);
        String suffix = String.valueOf(System.nanoTime());
        String normalToken = registerAndLogin("plain_" + suffix, "pass_" + suffix);

        // 非管理员访问统计/列表 → 40300
        assertCode(get("/api/admin/stats", "Bearer " + normalToken), 40300);
        assertCode(get("/api/admin/users", "Bearer " + normalToken), 40300);
        // 未登录 → 40100
        assertCode(get("/api/admin/stats", null), 40100);

        // 管理员可访问：统计非空，列表包含自己
        JsonNode stats = get("/api/admin/stats", "Bearer " + adminToken);
        assertCode(stats, 0);
        assertThat(stats.at("/data/totalUsers").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.at("/data/todayNew").asLong()).isGreaterThanOrEqualTo(1);

        JsonNode users = get("/api/admin/users?keyword=" + ADMIN_USERNAME, "Bearer " + adminToken);
        assertCode(users, 0);
        assertThat(users.at("/data/total").asLong()).isEqualTo(1);
        assertThat(users.at("/data/items/0/username").asText()).isEqualTo(ADMIN_USERNAME);
        assertThat(users.at("/data/items/0/admin").asBoolean()).isTrue();
    }

    @Test
    void banBlocksLoginAndUnbanRestores() throws Exception {
        String adminToken = registerAndLogin(ADMIN_USERNAME, ADMIN_PASSWORD);
        String suffix = String.valueOf(System.nanoTime());
        String targetUsername = "ban_target_" + suffix;
        String targetPassword = "pass_" + suffix;
        String targetToken = registerAndLogin(targetUsername, targetPassword);

        // 找到目标用户 id
        JsonNode found = get("/api/admin/users?keyword=" + targetUsername, "Bearer " + adminToken);
        long targetId = found.at("/data/items/0/id").asLong();
        assertThat(targetId).isPositive();

        // 封禁 → 状态变更且登录被拒（40300）
        assertCode(post("/api/admin/users/" + targetId + "/ban", null, "Bearer " + adminToken), 0);
        assertCode(post("/api/auth/login", Map.of("username", targetUsername, "password", targetPassword)), 40300);

        // 解封 → 登录恢复
        assertCode(post("/api/admin/users/" + targetId + "/unban", null, "Bearer " + adminToken), 0);
        assertCode(post("/api/auth/login", Map.of("username", targetUsername, "password", targetPassword)), 0);

        // 被封禁前签发的 token 在封禁期间仍可调 whoami（即时性由登录/续期保障），此处仅确认目标 id 语义
        assertThat(targetToken).isNotBlank();
    }

    @Test
    void adminAccountCannotBeBanned() throws Exception {
        String adminToken = registerAndLogin(ADMIN_USERNAME, ADMIN_PASSWORD);
        JsonNode found = get("/api/admin/users?keyword=" + ADMIN_USERNAME, "Bearer " + adminToken);
        long adminId = found.at("/data/items/0/id").asLong();

        // 封禁管理员 → 40300，且管理员仍可正常登录
        assertCode(post("/api/admin/users/" + adminId + "/ban", null, "Bearer " + adminToken), 40300);
        assertCode(post("/api/auth/login", Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD)), 0);
    }

    @Test
    void banMissingUserReturnsNotFound() throws Exception {
        String adminToken = registerAndLogin(ADMIN_USERNAME, ADMIN_PASSWORD);
        assertCode(post("/api/admin/users/999999/ban", null, "Bearer " + adminToken), 40400);
    }

    /** 注册（已存在则忽略）并登录，返回 access token */
    private String registerAndLogin(String username, String password) throws Exception {
        post("/api/auth/register", Map.of("username", username, "password", password));
        JsonNode login = post("/api/auth/login", Map.of("username", username, "password", password));
        assertCode(login, 0);
        return login.at("/data/token").asText();
    }

    private JsonNode post(String path, Map<String, Object> body) throws Exception {
        return post(path, body, null);
    }

    private JsonNode post(String path, Map<String, Object> body, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(
                        body == null ? "" : objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
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
