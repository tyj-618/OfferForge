package com.offerforge.report;

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
 * 报告链路端到端测试（test profile：mock provider、各阶段题量上限=1）：
 * 完整面试 → finish 生成报告 → 报告查询 → 多次面试后历史列表与进步曲线。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InterviewReportIntegrationTests {

    private static final String LONG_ANSWER = "我对这个问题有比较深入的理解，从底层原理到工程实践都做过总结，包括核心流程、常见陷阱以及对应的优化方案。";

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fullInterviewGeneratesQueryableReport() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);

        // start 携带岗位方向
        JsonNode start = post("/api/interview/start", token, Map.of("position", "Java 后端开发"));
        assertCode(start, 0);
        String sessionId = start.at("/data/sessionId").asText();

        runFullInterview(sessionId, token);

        // finish：结束面试并生成报告；3 题均 8 分 → 综合分 = 主问题平均分 × 10 = 80
        JsonNode finish = post("/api/interview/" + sessionId + "/finish", token, Map.of());
        assertCode(finish, 0);
        JsonNode report = finish.at("/data");
        assertThat(report.at("/interviewId").asText()).isEqualTo(sessionId);
        assertThat(report.at("/position").asText()).isEqualTo("Java 后端开发");
        assertThat(report.at("/totalQuestions").asInt()).isEqualTo(3);
        assertThat(report.at("/totalFollowUps").asInt()).isZero();
        assertThat(report.at("/overallScore").asDouble()).isEqualTo(80.0);
        assertThat(report.at("/rating").asText()).isEqualTo("良好");
        // 各维度平均分 = 各题维度平均（Mock 各维度与综合分同值）
        assertThat(report.at("/avgAccuracy").asDouble()).isEqualTo(8.0);
        assertThat(report.at("/avgCompleteness").asDouble()).isEqualTo(8.0);
        assertThat(report.at("/avgClarity").asDouble()).isEqualTo(8.0);
        assertThat(report.at("/avgDepth").asDouble()).isEqualTo(8.0);
        // 各阶段分 = 该阶段题目平均
        assertThat(report.at("/basicsScore").asDouble()).isEqualTo(8.0);
        assertThat(report.at("/projectScore").asDouble()).isEqualTo(8.0);
        assertThat(report.at("/deepScore").asDouble()).isEqualTo(8.0);
        // 逐题点评、亮点/薄弱点/建议齐备
        assertThat(report.at("/questionEvaluations").size()).isEqualTo(3);
        assertThat(report.at("/strengths").size()).isPositive();
        assertThat(report.at("/weaknesses").size()).isPositive();
        assertThat(report.at("/suggestions").size()).isPositive();

        // GET 查询报告与 finish 返回一致；重复 finish 幂等
        JsonNode fetched = get("/api/report/" + sessionId, token);
        assertCode(fetched, 0);
        assertThat(fetched.at("/data/overallScore").asDouble()).isEqualTo(80.0);
        assertThat(fetched.at("/data/questionEvaluations").size()).isEqualTo(3);
        assertCode(post("/api/interview/" + sessionId + "/finish", token, Map.of()), 0);
    }

    @Test
    void historyAndProgressReflectMultipleInterviews() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);

        // 两次完整面试（均 80 分）
        String first = post("/api/interview/start", token, Map.of()).at("/data/sessionId").asText();
        runFullInterview(first, token);
        assertCode(post("/api/interview/" + first + "/finish", token, Map.of()), 0);

        String second = post("/api/interview/start", token, Map.of()).at("/data/sessionId").asText();
        runFullInterview(second, token);
        assertCode(post("/api/interview/" + second + "/finish", token, Map.of()), 0);

        // 历史列表：2 条记录，倒序返回
        JsonNode history = get("/api/report/history?page=0&size=10", token);
        assertCode(history, 0);
        assertThat(history.at("/data/totalElements").asInt()).isEqualTo(2);
        assertThat(history.at("/data/content").size()).isEqualTo(2);
        assertThat(history.at("/data/content/0/overallScore").asDouble()).isEqualTo(80.0);
        assertThat(history.at("/data/content/0/status").asText()).isEqualTo("FINISHED");

        // 模式划分：未传 mode 默认实战归档；mode=practice 命中、mode=training 为空；非法 mode 拒绝
        assertThat(history.at("/data/content/0/mode").asText()).isEqualTo("practice");
        assertThat(get("/api/report/history?mode=practice", token).at("/data/totalElements").asInt()).isEqualTo(2);
        assertThat(get("/api/report/history?mode=training", token).at("/data/totalElements").asInt()).isZero();
        assertCode(get("/api/report/history?mode=invalid", token), 40000);

        // 进步曲线：最近 N 次，时间正序
        JsonNode progress = get("/api/report/progress?limit=10", token);
        assertCode(progress, 0);
        assertThat(progress.at("/data").size()).isEqualTo(2);
        assertThat(progress.at("/data/0/overallScore").asDouble()).isEqualTo(80.0);
        assertThat(progress.at("/data/1/overallScore").asDouble()).isEqualTo(80.0);

        // 归属校验：他人查询报告 → NOT_FOUND；未登录 → 未授权
        String tokenB = newUser();
        assertCode(get("/api/report/" + first, tokenB), 40400);
        assertCode(get("/api/report/history", null), 40100);
        assertCode(get("/api/report/progress", null), 40100);
    }

    /**
     * 完整面试流程：自我介绍 → 三道主问题（长回答均 8 分）→ 收尾回复进入 FINISHED。
     */
    private void runFullInterview(String sessionId, String token) throws Exception {
        ask(sessionId, token, "我熟悉 Java 后端开发，做过电商项目。");
        ask(sessionId, token, LONG_ANSWER);
        ask(sessionId, token, LONG_ANSWER);
        String closing = ask(sessionId, token, LONG_ANSWER);
        assertThat(closing).contains("\"state\":\"CLOSING\"");
        ask(sessionId, token, "谢谢面试官，期待反馈。");
    }

    private String newUser() throws Exception {
        String username = "report_user_" + System.nanoTime();
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
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
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

    private void assertCode(JsonNode response, int expected) {
        assertThat(response.at("/code").asInt()).as("response: %s", response).isEqualTo(expected);
    }
}
