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
 * 邮箱验证码认证集成测试（test profile：内存存储 + Mock 发信）：
 * 发码 → 邮箱注册（验证码+用户名+密码）→ 用户名/邮箱双入口登录 → 忘记密码重置；
 * 验证码一次性 / 防刷 / 错误锁定行为同步覆盖。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmailCodeAuthIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private EmailVerificationCodeStore codeStore;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendCodeRejectsInvalidEmailFormat() throws Exception {
        assertCode(post("/api/auth/send-code", Map.of("email", "not-an-email")), 40000);
    }

    @Test
    void emailRegisterThenLoginByUsernameOrEmail() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String email = "reg_" + suffix + "@example.com";
        String username = "reg_user_" + suffix;
        assertCode(post("/api/auth/send-code", Map.of("email", email)), 0);

        // 错误验证码 → 40002
        String wrongCode = codeStore.getCode(email).equals("000000") ? "111111" : "000000";
        assertCode(post("/api/auth/register",
                Map.of("email", email, "code", wrongCode, "username", username, "password", "123456")), 40002);

        // 正确验证码 → 注册成功（账号与邮箱一一对应）
        assertCode(post("/api/auth/register",
                Map.of("email", email, "code", codeStore.getCode(email), "username", username, "password", "123456")), 0);

        // 用户名登录
        assertCode(post("/api/auth/login", Map.of("username", username, "password", "123456")), 0);
        // 邮箱登录
        assertCode(post("/api/auth/login", Map.of("username", email, "password", "123456")), 0);

        // 验证码一次性：注册成功后码即被清除，同邮箱再注册先命中唯一性校验 40902
        assertThat(codeStore.getCode(email)).isNull();
        codeStore.saveCode(email, "424242");
        assertCode(post("/api/auth/register",
                Map.of("email", email, "code", "424242", "username", "other_" + suffix, "password", "123456")), 40902);
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String email = "dup_" + suffix + "@example.com";
        register(email, "dup_user_" + suffix, "123456");

        // 同邮箱再注册 → 40902（先于验证码校验，不消耗验证码）
        assertCode(post("/api/auth/send-code", Map.of("email", email)), 0);
        assertCode(post("/api/auth/register",
                Map.of("email", email, "code", codeStore.getCode(email),
                        "username", "dup_other_" + suffix, "password", "123456")), 40902);
    }

    @Test
    void resetPasswordAllowsLoginWithNewPasswordOnly() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String email = "reset_" + suffix + "@example.com";
        String username = "reset_user_" + suffix;
        register(email, username, "old-pass-1");

        // 未注册邮箱重置 → 40400（先于验证码校验）
        assertCode(post("/api/auth/reset-password",
                Map.of("email", "ghost_" + suffix + "@example.com", "code", "123456", "newPassword", "new-pass-1")), 40400);

        assertCode(post("/api/auth/send-code", Map.of("email", email)), 0);
        // 错误验证码 → 40002
        assertCode(post("/api/auth/reset-password",
                Map.of("email", email, "code", "999999", "newPassword", "new-pass-1")), 40002);
        // 正确验证码 → 重置成功
        assertCode(post("/api/auth/reset-password",
                Map.of("email", email, "code", codeStore.getCode(email), "newPassword", "new-pass-1")), 0);

        // 新密码可登录（用户名/邮箱双入口），旧密码失效
        assertCode(post("/api/auth/login", Map.of("username", username, "password", "new-pass-1")), 0);
        assertCode(post("/api/auth/login", Map.of("username", email, "password", "new-pass-1")), 0);
        assertCode(post("/api/auth/login", Map.of("username", username, "password", "old-pass-1")), 40001);
    }

    @Test
    void resendWithin60SecondsIsRejected() throws Exception {
        String email = "rate_" + System.nanoTime() + "@example.com";
        assertCode(post("/api/auth/send-code", Map.of("email", email)), 0);
        assertCode(post("/api/auth/send-code", Map.of("email", email)), 42900);
    }

    @Test
    void sixWrongAttemptsLockTheEmail() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String email = "lock_" + suffix + "@example.com";
        assertCode(post("/api/auth/send-code", Map.of("email", email)), 0);
        String code = codeStore.getCode(email);
        String wrongCode = code.equals("000000") ? "111111" : "000000";

        // 6 次错误（第 6 次触发锁定，响应仍是 40002）
        for (int i = 0; i < 6; i++) {
            assertCode(post("/api/auth/register",
                    Map.of("email", email, "code", wrongCode, "username", "lock_" + suffix + "_" + i, "password", "123456")), 40002);
        }
        // 锁定后即使正确验证码也被拒，且无法重新发码
        assertCode(post("/api/auth/register",
                Map.of("email", email, "code", code, "username", "lock_ok_" + suffix, "password", "123456")), 42900);
        assertCode(post("/api/auth/send-code", Map.of("email", email)), 42900);
    }

    /** 发码并注册新账号（测试辅助：直接写码绕过 60 秒防刷窗口） */
    private void register(String email, String username, String password) throws Exception {
        codeStore.saveCode(email, "246810");
        assertCode(post("/api/auth/register",
                Map.of("email", email, "code", "246810", "username", username, "password", password)), 0);
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

    private void assertCode(JsonNode response, int expected) {
        assertThat(response.at("/code").asInt()).as("response: %s", response).isEqualTo(expected);
    }
}
