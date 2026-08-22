package com.offerforge.resume;

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
 * 简历管理接口端到端测试：CRUD、section 查询、纯文本解析、鉴权与归属校验。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResumeIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private EmailVerificationCodeStore codeStore;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resumeCrudSectionAndParseFlow() throws Exception {
        UserToken user = newUser();

        // 创建简历（结构化项目经历）
        JsonNode created = post("/api/resume", user.token(), Map.of(
                "name", "张三",
                "education", "某大学 计算机本科",
                "skills", "Java, MySQL, Redis",
                "projects", List.of(Map.of(
                        "projectName", "秒杀系统",
                        "role", "后端负责人",
                        "duration", "2025.01-2025.06",
                        "description", "高并发秒杀平台",
                        "techStack", "Spring Boot, Redis, MQ",
                        "highlights", "支撑 10w QPS",
                        "challenges", "超卖问题")),
                "internships", "某公司后端实习",
                "selfIntroduction", "热爱后端开发"));
        assertCode(created, 0);
        long resumeId = created.at("/data/id").asLong();
        assertThat(resumeId).isPositive();
        assertThat(created.at("/data/projects/0/projectName").asText()).isEqualTo("秒杀系统");

        // 带 id 更新（全量覆盖语义）：改姓名，id 不变
        JsonNode updated = post("/api/resume", user.token(), Map.of(
                "id", resumeId,
                "name", "张三丰",
                "education", "某大学 计算机本科",
                "skills", "Java, MySQL, Redis, Kafka",
                "projects", List.of(Map.of(
                        "projectName", "秒杀系统",
                        "role", "后端负责人",
                        "techStack", "Spring Boot, Redis, MQ"))));
        assertCode(updated, 0);
        assertThat(updated.at("/data/id").asLong()).isEqualTo(resumeId);
        assertThat(updated.at("/data/name").asText()).isEqualTo("张三丰");

        // 列表与按用户查询
        JsonNode list = get("/api/resume/list", user.token());
        assertCode(list, 0);
        assertThat(list.at("/data").size()).isEqualTo(1);
        assertThat(list.at("/data/0/name").asText()).isEqualTo("张三丰");

        JsonNode latest = get("/api/resume/" + user.userId(), user.token());
        assertCode(latest, 0);
        assertThat(latest.at("/data/name").asText()).isEqualTo("张三丰");

        // 按 id 查详情（前端编辑页加载指定简历）
        JsonNode detail = get("/api/resume/detail/" + resumeId, user.token());
        assertCode(detail, 0);
        assertThat(detail.at("/data/id").asLong()).isEqualTo(resumeId);
        assertThat(detail.at("/data/projects/0/projectName").asText()).isEqualTo("秒杀系统");

        // section 查询（纯文本，供 Function Calling）
        JsonNode projects = get("/api/resume/" + user.userId() + "/section/projects?projectIndex=0", user.token());
        assertCode(projects, 0);
        assertThat(projects.at("/data").asText()).contains("秒杀系统").contains("技术栈：Spring Boot, Redis, MQ");

        JsonNode all = get("/api/resume/" + user.userId() + "/section/all", user.token());
        assertCode(all, 0);
        assertThat(all.at("/data").asText()).contains("姓名：张三丰").contains("【教育经历】");

        assertCode(get("/api/resume/" + user.userId() + "/section/unknown", user.token()), 40000);

        // 纯文本解析（不落库）：Mock 按「姓名/项目名称/技术栈」标记行确定性解析
        JsonNode parsed = post("/api/resume/parse", user.token(), Map.of(
                "rawText", "姓名：李四\n项目名称：商城系统\n技术栈：Java, Redis"));
        assertCode(parsed, 0);
        assertThat(parsed.at("/data/name").asText()).isEqualTo("李四");
        assertThat(parsed.at("/data/projects/0/projectName").asText()).isEqualTo("商城系统");

        // 删除后不可再查
        assertCode(delete("/api/resume/" + resumeId, user.token()), 0);
        assertCode(get("/api/resume/" + user.userId(), user.token()), 40400);
    }

    @Test
    void resumeEndpointsEnforceAuthAndOwnership() throws Exception {
        UserToken userA = newUser();
        JsonNode created = post("/api/resume", userA.token(), Map.of("name", "用户A"));
        assertCode(created, 0);
        long resumeId = created.at("/data/id").asLong();

        // 未登录
        assertCode(post("/api/resume", null, Map.of("name", "匿名")), 40100);
        assertCode(get("/api/resume/list", null), 40100);

        // 他人简历：路径 userId 与登录用户不一致 → 40300
        UserToken userB = newUser();
        assertCode(get("/api/resume/" + userA.userId(), userB.token()), 40300);
        assertCode(get("/api/resume/" + userA.userId() + "/section/all", userB.token()), 40300);
        // 按 id 查他人简历详情 → 40400（不暴露存在性）
        assertCode(get("/api/resume/detail/" + resumeId, userB.token()), 40400);

        // 删除他人简历 → 40400（不暴露存在性）
        assertCode(delete("/api/resume/" + resumeId, userB.token()), 40400);
    }

    private UserToken newUser() throws Exception {
        String username = "resume_user_" + System.nanoTime();
        String email = username.toLowerCase() + "@test.local";
        codeStore.saveCode(email, "135790");
        assertCode(post("/api/auth/register", null,
                Map.of("email", email, "code", "135790", "username", username, "password", "123456")), 0);
        JsonNode login = post("/api/auth/login", null, Map.of("username", username, "password", "123456"));
        assertCode(login, 0);
        return new UserToken(login.at("/data/token").asText(), login.at("/data/user/id").asLong());
    }

    private record UserToken(String token, long userId) {
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
