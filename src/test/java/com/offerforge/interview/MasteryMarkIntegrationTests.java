package com.offerforge.interview;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 掌握度标记（绿勾/红叉）端到端测试（test profile：各阶段题量上限=1，标记即推进阶段）。
 * mastered：题目直接 pass——不计分、不入作答历史、不计作答数，对应资料问答加绿勾；
 * dontknow：等价作答「不知道」（综合分强制 0），对应资料问答加红叉；
 * 两者仅训练模式出题阶段可用，实战模式拒绝；训练模式低分作答（<5）自动加红叉。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MasteryMarkIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private EmailVerificationCodeStore codeStore;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void masteredPassesQuestionWithoutScoreAndAddsCheck() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        // 掌握度标记仅训练模式可用：显式以训练模式开局
        String sessionId = post("/api/interview/start", token, Map.of("mode", "training")).at("/data/sessionId").asText();

        // 开场环节（自我介绍）无题可标记 → SSE error 40900
        assertThat(mark(sessionId, "mastered", token)).contains("event:error").contains("40900");
        assertThat(mark(sessionId, "dontknow", token)).contains("event:error").contains("40900");

        // 自我介绍作答 → 进入基础考察并出第 1 题
        assertThat(ask(sessionId, token, "我熟悉 Java 后端开发，做过电商项目。"))
                .contains("\"action\":\"ADVANCE\"").contains("\"state\":\"BASICS\"");
        String basicsQuestion = get("/api/interview/" + sessionId + "/status", token)
                .at("/data/currentQuestion").asText();
        assertThat(basicsQuestion).isNotBlank();

        // 已掌握：不计分（score null）、不入作答历史；各阶段 1 题 → 直接推进项目考察
        JsonNode masteredDone = doneOf(mark(sessionId, "mastered", token));
        assertThat(masteredDone.at("/score").isNull()).isTrue();
        assertThat(masteredDone.at("/action").asText()).isEqualTo("ADVANCE");
        assertThat(masteredDone.at("/status/state").asText()).isEqualTo("PROJECT");
        assertThat(masteredDone.at("/status/askedCount").asInt()).isZero();

        // 资料库官方列表该题出现绿勾
        assertThat(checksOf(get("/api/knowledge/official", token), basicsQuestion)).isEqualTo(1);

        // 逐题 mastered 推进：PROJECT → DEEP → CLOSING（项目内置题不在知识库，不记录标记）
        assertThat(mark(sessionId, "mastered", token))
                .contains("\"action\":\"ADVANCE\"").contains("\"state\":\"DEEP\"");
        assertThat(mark(sessionId, "mastered", token))
                .contains("\"action\":\"ADVANCE\"").contains("\"state\":\"CLOSING\"");

        // 收尾环节无题可标记 → SSE error 40900
        assertThat(mark(sessionId, "mastered", token)).contains("event:error").contains("40900");

        // 结束面试：3 题全部 mastered pass → 无作答记录，属短场（问答不足门槛）：
        // 不消耗免费次数且不记录历史，finish 返回 archived=false、无报告
        JsonNode finish = post("/api/interview/" + sessionId + "/finish", token, Map.of());
        assertCode(finish, 0);
        assertThat(finish.at("/data/archived").asBoolean()).isFalse();
        assertThat(finish.at("/data/report").isNull()).isTrue();
    }

    @Test
    void dontknowForcesZeroScoreAndAddsCross() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        String sessionId = post("/api/interview/start", token, Map.of("mode", "training")).at("/data/sessionId").asText();
        assertThat(ask(sessionId, token, "我熟悉 Java 后端开发，做过电商项目。"))
                .contains("\"state\":\"BASICS\"");
        String basicsQuestion = get("/api/interview/" + sessionId + "/status", token)
                .at("/data/currentQuestion").asText();

        // 不知道：走完整评估反馈流程（点评照常），综合分强制 0；
        // 0 分与正常低分作答一致触发追问，主问题计入作答数
        JsonNode dontknowDone = doneOf(mark(sessionId, "dontknow", token));
        assertThat(dontknowDone.at("/score").asDouble()).isZero();
        assertThat(dontknowDone.at("/action").asText()).isEqualTo("FOLLOW_UP");
        assertThat(dontknowDone.at("/status/state").asText()).isEqualTo("BASICS");
        assertThat(dontknowDone.at("/status/askedCount").asInt()).isEqualTo(1);

        // 资料库官方列表该题出现红叉
        assertThat(crossesOf(get("/api/knowledge/official", token), basicsQuestion)).isEqualTo(1);
    }

    @Test
    void lowScoreAnswerAddsCrossAutomatically() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        String sessionId = post("/api/interview/start", token, Map.of("mode", "training")).at("/data/sessionId").asText();
        assertThat(ask(sessionId, token, "我熟悉 Java 后端开发，做过电商项目。"))
                .contains("\"state\":\"BASICS\"");
        String basicsQuestion = get("/api/interview/" + sessionId + "/status", token)
                .at("/data/currentQuestion").asText();

        // Mock 模型对「嗯。」评 3 分（<5）：评分联动自动加红叉
        JsonNode done = doneOf(ask(sessionId, token, "嗯。"));
        assertThat(done.at("/score").asDouble()).isEqualTo(3.0);
        assertThat(crossesOf(get("/api/knowledge/official", token), basicsQuestion)).isEqualTo(1);
    }

    @Test
    void marksRejectedInPracticeMode() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        // 缺省开局即实战模式
        String sessionId = post("/api/interview/start", token, Map.of()).at("/data/sessionId").asText();
        assertThat(ask(sessionId, token, "我熟悉 Java 后端开发，做过电商项目。"))
                .contains("\"state\":\"BASICS\"");

        // 实战模式不提供掌握度标记 → SSE error 40900，面试照常继续
        assertThat(mark(sessionId, "mastered", token))
                .contains("event:error").contains("40900").contains("实战模式不提供此操作");
        assertThat(mark(sessionId, "dontknow", token))
                .contains("event:error").contains("40900").contains("实战模式不提供此操作");
    }

    private String newUser() throws Exception {
        String username = "mastery_user_" + System.nanoTime();
        String email = username.toLowerCase() + "@test.local";
        codeStore.saveCode(email, "135790");
        assertCode(post("/api/auth/register", null,
                Map.of("email", email, "code", "135790", "username", username, "password", "123456")), 0);
        JsonNode login = post("/api/auth/login", null, Map.of("username", username, "password", "123456"));
        assertCode(login, 0);
        return login.at("/data/token").asText();
    }

    /** mastered / dontknow 标记当前题（SSE，无请求体），返回完整事件流文本 */
    private String mark(String sessionId, String action, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/interview/" + sessionId + "/" + action))
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private String ask(String sessionId, String token, String message) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/interview/" + sessionId + "/ask"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of("message", message)), StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return response.body();
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

    /** 官方题库列表中指定题面的绿勾数（找不到该题视为 0） */
    private int checksOf(JsonNode officialListResponse, String question) {
        JsonNode item = findItem(officialListResponse, question);
        return item == null ? 0 : item.at("/checks").asInt();
    }

    /** 官方题库列表中指定题面的红叉数（找不到该题视为 0） */
    private int crossesOf(JsonNode officialListResponse, String question) {
        JsonNode item = findItem(officialListResponse, question);
        return item == null ? 0 : item.at("/crosses").asInt();
    }

    private JsonNode findItem(JsonNode officialListResponse, String question) {
        for (JsonNode item : officialListResponse.at("/data")) {
            if (question.equals(item.at("/question").asText())) {
                return item;
            }
        }
        return null;
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
