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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分组选题集成测试（任务 8）：面试开始勾选资料分组后，BASICS 出题仅来自勾选分组；
 * 用户上传的私有题目也能进入本人面试题库，未勾选时不出现。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InterviewCategorySelectionIntegrationTests {

    private static final String PRIVATE_CATEGORY = "内部专题";
    private static final String PRIVATE_QUESTION = "内部专题题：灰度发布如何实施？";
    private static final String UPLOAD_CONTENT = """
            Q: 内部专题题：灰度发布如何实施？
            A: 流量打标 + 路由到灰度实例。
            """;

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void selectedCategoriesRestrictQuestionPoolToPrivateUpload() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        JsonNode upload = uploadMultipart(token, "notes.md", UPLOAD_CONTENT, PRIVATE_CATEGORY);
        assertCode(upload, 0);
        assertThat(upload.at("/data/inserted").asInt()).isEqualTo(1);

        // 分组接口可见本人自定义分组
        JsonNode categories = get("/api/knowledge/categories", token);
        assertThat(categories.at("/data/custom").toString()).contains(PRIVATE_CATEGORY);

        // 勾选自定义分组开始面试
        JsonNode start = post("/api/interview/start", token, Map.of("categories", List.of(PRIVATE_CATEGORY)));
        assertCode(start, 0);
        String sessionId = start.at("/data/sessionId").asText();

        // 自我介绍后进入 BASICS：候选池仅私有题一道，必出该题
        ask(sessionId, token, "自我介绍：熟悉 Java 后端。");
        JsonNode status = get("/api/interview/" + sessionId + "/status", token);
        assertThat(status.at("/data/currentQuestion").asText()).isEqualTo(PRIVATE_QUESTION);
    }

    @Test
    void defaultPoolDoesNotContainPrivateUploadWhenUnselected() throws Exception {
        String token = newUser();
        assertCode(post("/api/knowledge/import", token, Map.of()), 0);
        assertCode(uploadMultipart(token, "notes.md", UPLOAD_CONTENT, PRIVATE_CATEGORY), 0);

        // 不勾选分组：按阶段默认官方分组出题，私有题不应出现
        JsonNode start = post("/api/interview/start", token, Map.of());
        String sessionId = start.at("/data/sessionId").asText();
        ask(sessionId, token, "自我介绍：熟悉 Java 后端。");
        JsonNode status = get("/api/interview/" + sessionId + "/status", token);
        assertThat(status.at("/data/currentQuestion").asText())
                .isNotBlank()
                .isNotEqualTo(PRIVATE_QUESTION);
    }

    private String newUser() throws Exception {
        String username = "category_user_" + System.nanoTime();
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

    /** multipart/form-data 上传（file + 可选 category 字段） */
    private JsonNode uploadMultipart(String token, String filename, String content, String category) throws Exception {
        String boundary = "eofBoundary" + System.nanoTime();
        StringBuilder body = new StringBuilder();
        body.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"\r\n")
                .append("Content-Type: text/markdown\r\n\r\n")
                .append(content).append("\r\n");
        if (category != null) {
            body.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"category\"\r\n\r\n")
                    .append(category).append("\r\n");
        }
        body.append("--").append(boundary).append("--\r\n");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/knowledge/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
