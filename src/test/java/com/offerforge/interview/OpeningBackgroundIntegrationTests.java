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
 * 开场自我介绍深挖与背景复用端到端（test profile：mock provider、各阶段题量上限=1）：
 * <ul>
 *   <li>训练模式开场作答评分 + 具体分析（仅展示不入报告、不计题数/平均分）</li>
 *   <li>未选简历时开场对话收集的自述背景驱动 PROJECT 阶段针对性出题</li>
 *   <li>报告统计与逐题评估排除开场记录</li>
 * </ul>
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpeningBackgroundIntegrationTests {

    private static final String LONG_INTRO = "老师您好，我熟悉 Java 后端开发，独立做过校园论坛项目，负责用户认证、帖子发布与缓存穿透治理等核心模块。";
    private static final String LONG_ANSWER = "我对这个问题有比较深入的理解，从底层原理到工程实践都做过总结，包括核心流程、常见陷阱以及对应的优化方案。";

    @LocalServerPort
    private int port;

    @Autowired
    private EmailVerificationCodeStore codeStore;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void backgroundCollectedFromOpeningDrivesProjectQuestionsWithoutResume() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        String sessionId = post("/api/interview/start", token, Map.of()).at("/data/sessionId").asText();

        // 开场信息充分 → 直接推进基础考察（实战模式免评分）
        String opening = ask(sessionId, token, "我熟悉 Java 后端开发，做过电商项目。");
        assertThat(opening).contains("\"score\":null")
                .contains("\"action\":\"ADVANCE\"").contains("\"state\":\"BASICS\"");

        // 基础题长回答 → 推进 PROJECT；未选简历时项目题由开场收集的自述背景生成（mock 背景含「面试对话项目」）
        String project = ask(sessionId, token, LONG_ANSWER);
        assertThat(project).contains("面试对话项目").contains("\"state\":\"PROJECT\"");
    }

    @Test
    void trainingOpeningAnswerScoredAndExcludedFromReport() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        String sessionId = post("/api/interview/start", token, Map.of("mode", "training"))
                .at("/data/sessionId").asText();

        // 开场长自我介绍（≥30 字）→ 训练模式评分 8 + 详细反馈，并照常推进基础考察
        String opening = ask(sessionId, token, LONG_INTRO);
        assertThat(opening).contains("正在评估你的自我介绍")
                .contains("\"score\":8").contains("\"goodPoints\"").contains("\"improvedAnswer\"")
                .contains("\"action\":\"ADVANCE\"").contains("\"state\":\"BASICS\"");

        // 开场记录入 history 供刷新回放（题面为 null、知识点「自我介绍」），但不计已问题数
        JsonNode status = get("/api/interview/" + sessionId + "/status", token);
        assertThat(status.at("/data/askedCount").asInt()).isZero();
        JsonNode history = status.at("/data/history");
        assertThat(history.size()).isEqualTo(1);
        assertThat(history.at("/0/state").asText()).isEqualTo("OPENING");
        assertThat(history.at("/0/knowledgePoint").asText()).isEqualTo("自我介绍");
        assertThat(history.at("/0/score").asDouble()).isEqualTo(8.0);
        // 开场环节无待答题：「深入该模块」依赖的最近非开场分组为空
        assertThat(status.at("/data/lastAnswerCategory").isNull()).isTrue();

        // 走完剩余流程：三道主问题长回答均 8 分 → 收尾 → 结束生成报告
        ask(sessionId, token, LONG_ANSWER);
        ask(sessionId, token, LONG_ANSWER);
        String closing = ask(sessionId, token, LONG_ANSWER);
        assertThat(closing).contains("\"state\":\"CLOSING\"");
        ask(sessionId, token, "谢谢面试官，期待反馈。");

        JsonNode finish = post("/api/interview/" + sessionId + "/finish", token, Map.of());
        assertCode(finish, 0);
        JsonNode report = finish.at("/data");
        // 开场评分不入报告：题数/逐题评估均不含开场记录，综合分不受影响
        assertThat(report.at("/totalQuestions").asInt()).isEqualTo(3);
        assertThat(report.at("/overallScore").asDouble()).isEqualTo(80.0);
        JsonNode evaluations = report.at("/questionEvaluations");
        assertThat(evaluations.size()).isEqualTo(3);
        for (JsonNode evaluation : evaluations) {
            assertThat(evaluation.at("/state").asText()).isNotEqualTo("OPENING");
        }
    }

    private String newUser() throws Exception {
        String username = "opening_bg_user_" + System.nanoTime();
        String email = username.toLowerCase() + "@test.local";
        codeStore.saveCode(email, "135790");
        assertCode(post("/api/auth/register", null,
                Map.of("email", email, "code", "135790", "username", username, "password", "123456")), 0);
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
