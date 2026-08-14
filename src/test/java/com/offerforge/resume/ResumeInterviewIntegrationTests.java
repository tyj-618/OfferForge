package com.offerforge.resume;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 简历关联面试端到端测试（test profile：各阶段题量上限=1）：
 * 项目题基于简历项目生成、深挖题基于项目回答追问、无简历降级通用项目题。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResumeInterviewIntegrationTests {

    private static final String LONG_ANSWER = "我对这个问题有比较深入的理解，从底层原理到工程实践都做过总结，包括核心流程、常见陷阱以及对应的优化方案。";

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resumeDrivenInterviewAsksProjectAndDeepQuestions() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);

        // 创建简历并关联开始面试
        JsonNode resume = post("/api/resume", token, Map.of(
                "name", "张三",
                "projects", List.of(Map.of(
                        "projectName", "秒杀系统",
                        "role", "后端负责人",
                        "description", "高并发秒杀平台",
                        "techStack", "Spring Boot, Redis, MQ",
                        "highlights", "支撑 10w QPS"))));
        assertCode(resume, 0);
        long resumeId = resume.at("/data/id").asLong();

        JsonNode start = post("/api/interview/start", token,
                Map.of("position", "Java 后端开发", "resumeId", resumeId));
        assertCode(start, 0);
        String sessionId = start.at("/data/sessionId").asText();

        // OPENING → BASICS
        ask(sessionId, token, "我熟悉 Java 后端开发，做过秒杀系统。");
        // BASICS 长回答 8 分 → 推进 PROJECT，项目题基于简历「秒杀系统」生成
        String sseBasics = ask(sessionId, token, LONG_ANSWER);
        assertThat(sseBasics).contains("\"action\":\"ADVANCE\"").contains("\"state\":\"PROJECT\"");
        assertThat(sseBasics).contains("秒杀系统");

        // PROJECT 长回答 8 分 → 推进 DEEP，深挖题基于刚才的项目问题与回答生成
        String sseProject = ask(sessionId, token, LONG_ANSWER);
        assertThat(sseProject).contains("\"action\":\"ADVANCE\"").contains("\"state\":\"DEEP\"");
        assertThat(sseProject).contains("深挖追问").contains("整体架构");

        // DEEP → CLOSING → FINISHED
        String sseDeep = ask(sessionId, token, LONG_ANSWER);
        assertThat(sseDeep).contains("\"state\":\"CLOSING\"");
        ask(sessionId, token, "谢谢面试官。");

        // 报告：3 题均 8 分；逐题题面可验证项目题/深挖题与简历的关联
        JsonNode finish = post("/api/interview/" + sessionId + "/finish", token, Map.of());
        assertCode(finish, 0);
        assertThat(finish.at("/data/totalQuestions").asInt()).isEqualTo(3);
        assertThat(finish.at("/data/overallScore").asDouble()).isEqualTo(80.0);
        assertThat(finish.at("/data/questionEvaluations/1/question").asText()).contains("秒杀系统");
        assertThat(finish.at("/data/questionEvaluations/2/question").asText()).contains("深挖追问");
    }

    @Test
    void startWithOthersResumeRejected() throws Exception {
        String tokenA = newUser();
        JsonNode resume = post("/api/resume", tokenA, Map.of("name", "用户A"));
        assertCode(resume, 0);
        long resumeId = resume.at("/data/id").asLong();

        String tokenB = newUser();
        assertCode(post("/api/interview/start", tokenB, Map.of("resumeId", resumeId)), 40400);
        assertCode(post("/api/interview/start", tokenB, Map.of("resumeId", 999999)), 40400);
    }

    @Test
    void noResumeFallsBackToGenericProjectQuestions() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        String sessionId = post("/api/interview/start", token, Map.of()).at("/data/sessionId").asText();

        ask(sessionId, token, "自我介绍：熟悉 Java。");
        String sseBasics = ask(sessionId, token, LONG_ANSWER);
        assertThat(sseBasics).contains("\"state\":\"PROJECT\"");
        // 无简历：PROJECT 阶段使用题库通用项目题（不携带具体项目名）
        assertThat(sseBasics).doesNotContain("秒杀系统").contains("参与度最高的项目");

        String sseProject = ask(sessionId, token, LONG_ANSWER);
        assertThat(sseProject).contains("\"state\":\"DEEP\"");
        // DEEP 仍基于 PROJECT 阶段的通用项目题回答生成深挖（不携带具体项目名）
        assertThat(sseProject).contains("深挖追问").contains("参与度最高的项目");

        ask(sessionId, token, LONG_ANSWER);
        ask(sessionId, token, "结束");
        JsonNode finish = post("/api/interview/" + sessionId + "/finish", token, Map.of());
        assertCode(finish, 0);
        assertThat(finish.at("/data/totalQuestions").asInt()).isEqualTo(3);
    }

    private String newUser() throws Exception {
        String username = "res_iv_" + System.nanoTime();
        assertCode(post("/api/auth/register", null, Map.of("username", username, "password", "123456")), 0);
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
