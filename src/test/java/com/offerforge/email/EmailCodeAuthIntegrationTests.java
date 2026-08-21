package com.offerforge.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 邮箱验证码登录集成测试（test profile：内存存储 + Mock 发信）：
 * 发码 → 错误码拒绝 → 正确码自动注册并签发会话 → 验证码一次性 → 防刷 → 错误锁定。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmailCodeAuthIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private MockVerificationMailSender mailSender;

    @Autowired
    private EmailVerificationCodeStore codeStore;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendCodeRejectsInvalidEmailFormat() throws Exception {
        assertCode(post("/api/auth/send-code", Map.of("email", "not-an-email")), 40000);
    }

    @Test
    void loginByCodeAutoRegistersAndIssuesToken() throws Exception {
        String email = "new_user_" + System.nanoTime() + "@example.com";
        assertCode(post("/api/auth/send-code", Map.of("email", email)), 0);
        String code = lastCodeFor(email);
        assertThat(code).matches("\\d{6}");

        // 错误验证码 → 40002
        assertCode(post("/api/auth/login-by-code", Map.of("email", email, "code", "000000".equals(code) ? "111111" : "000000")), 40002);

        // 正确验证码 → 自动注册 + 签发会话
        JsonNode login = post("/api/auth/login-by-code", Map.of("email", email, "code", code));
        assertCode(login, 0);
        assertThat(login.at("/data/token").asText()).isNotBlank();
        assertThat(login.at("/data/refreshToken").asText()).isNotBlank();
        assertThat(login.at("/data/user/username").asText()).startsWith("u_");

        // 验证码一次性：重复使用被拒
        assertCode(post("/api/auth/login-by-code", Map.of("email", email, "code", code)), 40002);

        // 会话可用：/api/auth/me 返回自动注册的用户
        JsonNode me = get("/api/auth/me", "Bearer " + login.at("/data/token").asText());
        assertCode(me, 0);
        assertThat(me.at("/data/username").asText()).startsWith("u_");
    }

    @Test
    void secondLoginWithSameEmailReusesAccount() throws Exception {
        String email = "reuse_" + System.nanoTime() + "@example.com";
        assertCode(post("/api/auth/send-code", Map.of("email", email)), 0);
        JsonNode first = post("/api/auth/login-by-code", Map.of("email", email, "code", lastCodeFor(email)));
        assertCode(first, 0);
        long firstUserId = first.at("/data/user/id").asLong();

        // 绕过 60 秒防刷窗口：直接向存储写入新码，验证同邮箱二次登录复用同一账号（不重复注册）
        codeStore.saveCode(email, "424242");
        JsonNode second = post("/api/auth/login-by-code", Map.of("email", email, "code", "424242"));
        assertCode(second, 0);
        assertThat(second.at("/data/user/id").asLong()).isEqualTo(firstUserId);
    }

    @Test
    void resendWithin60SecondsIsRejected() throws Exception {
        String email = "rate_" + System.nanoTime() + "@example.com";
        assertCode(post("/api/auth/send-code", Map.of("email", email)), 0);
        assertCode(post("/api/auth/send-code", Map.of("email", email)), 42900);
    }

    @Test
    void sixWrongAttemptsLockTheEmail() throws Exception {
        String email = "lock_" + System.nanoTime() + "@example.com";
        assertCode(post("/api/auth/send-code", Map.of("email", email)), 0);
        String code = lastCodeFor(email);

        // 6 次错误（第 6 次触发锁定，响应仍是 40002）
        for (int i = 0; i < 6; i++) {
            assertCode(post("/api/auth/login-by-code",
                    Map.of("email", email, "code", code.equals("000000") ? "111111" : "000000")), 40002);
        }
        // 锁定后即使正确验证码也被拒，且无法重新发码
        assertCode(post("/api/auth/login-by-code", Map.of("email", email, "code", code)), 42900);
        assertCode(post("/api/auth/send-code", Map.of("email", email)), 42900);
    }

    /** 从 Mock 发信记录中取目标邮箱最近一次验证码 */
    private String lastCodeFor(String email) {
        return mailSender.getSentMails().stream()
                .filter(mail -> mail[0].equals(email))
                .reduce((first, second) -> second)
                .map(mail -> mail[1])
                .orElseThrow(() -> new AssertionError("未找到发往 " + email + " 的验证码邮件"));
    }

    private JsonNode post(String path, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
