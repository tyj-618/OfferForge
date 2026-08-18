package com.offerforge.interview;

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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * 任务 4 集成测试：用户主导「深入模块」跳转-暂存-恢复全流程。
 * 面试（训练模式）作答后 status 暴露 lastAnswerCategory；凭该分组开启专项训练期间面试会话保持，
 * 训练结束后 active-session 仍可恢复原面试接着作答。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InterviewTrainingHandoffIntegrationTests {

    private static final Set<String> BASICS_CATEGORIES = Set.of("Java基础", "Java集合", "计算机网络");
    /** 非终态阶段：测试环境每阶段仅 1 题，作答后可能已推进，断言只要求会话仍在进行中 */
    private static final Set<String> ACTIVE_STATES = Set.of("OPENING", "BASICS", "PROJECT", "DEEP", "CLOSING");
    /** ≥30 字：Mock 模型评 8 分 */
    private static final String LONG_ANSWER = "我从底层原理讲起，结合实际应用场景，先总结核心要点和常见误区，再补充性能优化与线上排查的实践经验。";
    private static final String SELF_INTRO = "你好，我是一名 Java 后端开发者，熟悉 Spring 与 MySQL，做过高并发项目。";

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deepDiveToTrainingKeepsInterviewResumable() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);

        // 无进行中会话：active-session 返回 null
        JsonNode none = get("/api/interview/active-session", token);
        assertCode(none, 0);
        assertThat(none.at("/data").isNull()).isTrue();

        // 开始训练模式面试并作答：开场自我介绍 + 第一道基础题。
        // 随机选题可能落到 EASY 档题目不足 3 道的分组（如 Java集合仅 1 道），
        // 导致专项训练提前完成无法验证 3 题回合，此处重试至命中题量充足的场次。
        String interviewId = null;
        String category = null;
        for (int attempt = 0; attempt < 8 && category == null; attempt++) {
            if (interviewId != null) {
                post("/api/interview/" + interviewId + "/finish", token, Map.of());
            }
            JsonNode start = post("/api/interview/start", token,
                    Map.of("position", "Java 后端工程师", "mode", "training"));
            assertCode(start, 0);
            interviewId = start.at("/data/sessionId").asText();
            assertDone(doneOf(ask(interviewId, token, SELF_INTRO)));
            JsonNode firstDone = doneOf(ask(interviewId, token, LONG_ANSWER));
            assertThat(firstDone.at("/score").asDouble()).isEqualTo(8.0);

            // 反馈后 status 暴露最近作答题所属分组（BASICS 官方分组之一），即「深入该模块」的跳转目标
            JsonNode status = get("/api/interview/" + interviewId + "/status", token);
            String candidate = status.at("/data/lastAnswerCategory").asText();
            assertThat(BASICS_CATEGORIES).contains(candidate);
            // 计算机网络的 EASY 档有 3 道题，可完整走完 3 题训练；其余分组题量不足时重试
            if ("计算机网络".equals(candidate)) {
                category = candidate;
            }
        }
        assertThat(category).as("多次重试仍未抽中题量充足的分组").isNotNull();

        // active-session 返回同一场面试，供「继续未完成的面试」恢复
        JsonNode active = get("/api/interview/active-session", token);
        assertCode(active, 0);
        assertThat(active.at("/data/sessionId").asText()).isEqualTo(interviewId);
        assertThat(ACTIVE_STATES).contains(active.at("/data/state").asText());

        // 跳转该分组开启专项训练：与面试会话并存互不冲突
        JsonNode training = post("/api/training/start", token, Map.of("category", category));
        assertCode(training, 0);
        String trainingId = training.at("/data/sessionId").asText();
        for (int i = 0; i < 3; i++) {
            assertDone(doneOf(answer(trainingId, token, LONG_ANSWER)));
        }
        JsonNode trainingStatus = get("/api/training/" + trainingId + "/status", token);
        assertThat(trainingStatus.at("/data/finished").asBoolean()).isTrue();

        // 训练结束回到面试：会话仍是同一场且未终态，接着作答照常推进
        JsonNode resumed = get("/api/interview/active-session", token);
        assertThat(resumed.at("/data/sessionId").asText()).isEqualTo(interviewId);
        assertThat(ACTIVE_STATES).contains(resumed.at("/data/state").asText());
        JsonNode continueDone = doneOf(ask(interviewId, token, LONG_ANSWER));
        assertThat(continueDone.at("/status/sessionId").asText()).isEqualTo(interviewId);
        assertThat(continueDone.at("/status/state").asText()).isNotEqualTo("FINISHED");
    }

    @Test
    void lastAnswerCategoryAbsentBeforeAnyAnswer() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);

        JsonNode start = post("/api/interview/start", token, Map.of("mode", "training"));
        assertCode(start, 0);
        // 尚未作答：lastAnswerCategory 为 null
        assertThat(start.at("/data/status/lastAnswerCategory").isNull()).isTrue();

        // 作答后正常结束面试：active-session 清空
        String interviewId = start.at("/data/sessionId").asText();
        ask(interviewId, token, SELF_INTRO);
        ask(interviewId, token, LONG_ANSWER);
        assertCode(post("/api/interview/" + interviewId + "/finish", token, Map.of()), 0);
        JsonNode afterFinish = get("/api/interview/active-session", token);
        assertCode(afterFinish, 0);
        assertThat(afterFinish.at("/data").isNull()).isTrue();
    }

    private String newUser() throws Exception {
        String username = "handoff_user_" + System.nanoTime();
        assertCode(post("/api/auth/register", null, Map.of("username", username, "password", "123456")), 0);
        JsonNode login = post("/api/auth/login", null, Map.of("username", username, "password", "123456"));
        assertCode(login, 0);
        return login.at("/data/token").asText();
    }

    private String ask(String sessionId, String token, String message) throws Exception {
        return ssePost("/api/interview/" + sessionId + "/ask", token, message);
    }

    private String answer(String sessionId, String token, String message) throws Exception {
        return ssePost("/api/training/" + sessionId + "/answer", token, message);
    }

    private String ssePost(String path, String token, String message) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
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

    /** done 载荷非空即视为 SSE 回合正常收尾（具体字段由各用例自行断言） */
    private void assertDone(JsonNode donePayload) {
        assertThat(donePayload.isMissingNode() || donePayload.isNull())
                .as("done payload: %s", donePayload).isFalse();
    }
}
