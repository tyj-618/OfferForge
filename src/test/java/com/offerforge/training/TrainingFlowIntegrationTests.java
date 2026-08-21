package com.offerforge.training;

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
 * 专项训练流程集成测试（任务 7）：选题递进、连续高分难度升档、达标题数完成归档、
 * 提前结束归档与状态校验。测试环境 max-questions=3，Mock 模型按回答长度分档评分。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrainingFlowIntegrationTests {

    private static final String CATEGORY = "Java并发";
    /** ≥30 字：Mock 模型评 8 分（连续高分触发升档） */
    private static final String LONG_ANSWER = "我从底层原理讲起，结合实际应用场景，先总结核心要点和常见误区，再补充性能优化与线上排查的实践经验。";

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fullTrainingFlowRaisesDifficultyAndArchivesRecord() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);

        JsonNode start = post("/api/training/start", token, Map.of("category", CATEGORY));
        assertCode(start, 0);
        String sessionId = start.at("/data/sessionId").asText();
        // 开场即出第 1 题（教练话术包装），EASY 起步
        assertThat(start.at("/data/openingMessage").asText()).contains("【专项训练】");
        assertThat(start.at("/data/status/currentDifficulty").asText()).isEqualTo("EASY");
        assertThat(start.at("/data/status/askedCount").asInt()).isZero();

        // 第 1 题作答：8 分，连击 1 次，难度仍 EASY
        JsonNode done1 = doneOf(answer(sessionId, token, LONG_ANSWER));
        assertThat(done1.at("/score").asDouble()).isEqualTo(8.0);
        assertThat(done1.at("/finished").asBoolean()).isFalse();
        assertThat(done1.at("/evaluation/improvedAnswer").asText()).isNotBlank();
        JsonNode status1 = get("/api/training/" + sessionId + "/status", token);
        assertThat(status1.at("/data/askedCount").asInt()).isEqualTo(1);
        assertThat(status1.at("/data/currentDifficulty").asText()).isEqualTo("EASY");

        // 第 2 题作答：连续 2 次高分，升档 MEDIUM 后出第 3 题
        JsonNode done2 = doneOf(answer(sessionId, token, LONG_ANSWER));
        assertThat(done2.at("/finished").asBoolean()).isFalse();
        JsonNode status2 = get("/api/training/" + sessionId + "/status", token);
        assertThat(status2.at("/data/askedCount").asInt()).isEqualTo(2);
        assertThat(status2.at("/data/currentDifficulty").asText()).isEqualTo("MEDIUM");

        // 第 3 题作答达上限：完成归档
        JsonNode done3 = doneOf(answer(sessionId, token, LONG_ANSWER));
        assertThat(done3.at("/finished").asBoolean()).isTrue();
        assertThat(done3.at("/status/finished").asBoolean()).isTrue();
        assertThat(done3.at("/status/askedCount").asInt()).isEqualTo(3);
        assertThat(done3.at("/status/averageScore").asDouble()).isEqualTo(8.0);
        assertThat(done3.at("/status/maxDifficultyReached").asText()).isEqualTo("MEDIUM");

        // 归档成绩入库：records 分页可见本人训练记录
        JsonNode records = get("/api/training/records?page=0&size=5", token);
        assertCode(records, 0);
        JsonNode first = records.at("/data/content/0");
        assertThat(first.at("/category").asText()).isEqualTo(CATEGORY);
        assertThat(first.at("/askedCount").asInt()).isEqualTo(3);
        assertThat(first.at("/averageScore").asDouble()).isEqualTo(80.0);
        assertThat(first.at("/maxDifficulty").asText()).isEqualTo("MEDIUM");

        // 训练报告：概要 + 逐题明细（题面/回答/导师点评/详细评估）齐备
        long recordId = first.at("/id").asLong();
        JsonNode report = get("/api/training/records/" + recordId + "/report", token);
        assertCode(report, 0);
        assertThat(report.at("/data/category").asText()).isEqualTo(CATEGORY);
        assertThat(report.at("/data/askedCount").asInt()).isEqualTo(3);
        assertThat(report.at("/data/averageScore").asDouble()).isEqualTo(80.0);
        assertThat(report.at("/data/rating").asText()).isEqualTo("良好");
        assertThat(report.at("/data/details").size()).isEqualTo(3);
        JsonNode detail = report.at("/data/details/0");
        assertThat(detail.at("/question").asText()).isNotBlank();
        assertThat(detail.at("/answer").asText()).isEqualTo(LONG_ANSWER);
        assertThat(detail.at("/comment").asText()).isNotBlank();
        assertThat(detail.at("/score").asDouble()).isEqualTo(8.0);
        assertThat(detail.at("/evaluation/improvedAnswer").asText()).isNotBlank();
        // 归属校验：他人查询训练报告 → NOT_FOUND
        assertCode(get("/api/training/records/" + recordId + "/report", newUser()), 40400);

        // 已完成的会话继续作答被拒绝（error 事件收尾）
        assertThat(answer(sessionId, token, LONG_ANSWER)).contains("event:error");
    }

    @Test
    void sessionStatusSurvivesRefreshAndFinishEarlyArchives() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);

        JsonNode start = post("/api/training/start", token, Map.of("category", CATEGORY));
        assertCode(start, 0);
        String sessionId = start.at("/data/sessionId").asText();
        answer(sessionId, token, LONG_ANSWER);

        // 刷新恢复：status 端点返回既有进度，history 携带已作答回合供前端完整重建对话
        JsonNode status = get("/api/training/" + sessionId + "/status", token);
        assertCode(status, 0);
        assertThat(status.at("/data/askedCount").asInt()).isEqualTo(1);
        assertThat(status.at("/data/finished").asBoolean()).isFalse();
        assertThat(status.at("/data/evaluating").asBoolean()).isFalse();
        assertThat(status.at("/data/currentQuestion").asText()).isNotBlank();
        JsonNode history = status.at("/data/history/0");
        assertThat(history.at("/question").asText()).isNotBlank();
        assertThat(history.at("/answer").asText()).isEqualTo(LONG_ANSWER);
        assertThat(history.at("/comment").asText()).isNotBlank();
        assertThat(history.at("/score").asDouble()).isEqualTo(8.0);
        assertThat(history.at("/evaluation/improvedAnswer").asText()).isNotBlank();

        // 主动结束：归档已作答成绩
        JsonNode finish = post("/api/training/" + sessionId + "/finish", token, Map.of());
        assertCode(finish, 0);
        assertThat(finish.at("/data/finished").asBoolean()).isTrue();
        assertThat(finish.at("/data/askedCount").asInt()).isEqualTo(1);

        JsonNode records = get("/api/training/records", token);
        assertThat(records.at("/data/content/0/askedCount").asInt()).isEqualTo(1);
        // 提前结束的归档同样携带逐题明细，报告页可查看完整回合
        long recordId = records.at("/data/content/0/id").asLong();
        JsonNode report = get("/api/training/records/" + recordId + "/report", token);
        assertCode(report, 0);
        assertThat(report.at("/data/details").size()).isEqualTo(1);
        assertThat(report.at("/data/details/0/answer").asText()).isEqualTo(LONG_ANSWER);
    }

    @Test
    void startValidationRejectsBlankOrEmptyCategory() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);

        // 空分组：参数错误
        assertThat(post("/api/training/start", token, Map.of()).at("/code").asInt()).isNotZero();
        // 分组无可见题目：参数错误
        assertThat(post("/api/training/start", token, Map.of("category", "不存在的分组"))
                .at("/code").asInt()).isNotZero();
    }

    @Test
    void onlyOneActiveTrainingSessionPerUser() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);

        assertCode(post("/api/training/start", token, Map.of("category", CATEGORY)), 0);
        // 已有进行中的训练：拒绝重复开始
        assertThat(post("/api/training/start", token, Map.of("category", CATEGORY))
                .at("/code").asInt()).isNotZero();
    }

    private String newUser() throws Exception {
        String username = "training_user_" + System.nanoTime();
        assertCode(post("/api/auth/register", null, Map.of("username", username, "password", "123456")), 0);
        JsonNode login = post("/api/auth/login", null, Map.of("username", username, "password", "123456"));
        assertCode(login, 0);
        return login.at("/data/token").asText();
    }

    private String answer(String sessionId, String token, String message) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/training/" + sessionId + "/answer"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of("message", message)), StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    /** 从 SSE 响应体中提取 done 事件载荷 JSON */
    private JsonNode doneOf(String sseBody) throws Exception {
        String[] lines = sseBody.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].startsWith("event:done") && lines[i + 1].startsWith("data:")) {
                return objectMapper.readTree(lines[i + 1].substring("data:".length()).trim());
            }
        }
        throw new AssertionError("SSE 响应缺少 done 事件：" + sseBody);
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private void assertCode(JsonNode response, int expected) {
        assertThat(response.at("/code").asInt()).as("response: %s", response).isEqualTo(expected);
    }
}
