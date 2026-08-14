package com.offerforge.qa;

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

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QaApiIntegrationTests {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void askReturnsAnswerWithKnowledgeReferences() throws Exception {
        String username = "qa_user_" + System.nanoTime();
        register(username, "123456");
        String token = login(username, "123456");

        JsonNode importResult = post("/api/knowledge/import", token, Map.of());
        assertCode(importResult, 0);

        JsonNode askResult = post("/api/qa/ask", token, Map.of("question", "HashMap 的底层原理是什么？"));
        assertCode(askResult, 0);
        assertThat(askResult.at("/data/question").asText()).isEqualTo("HashMap 的底层原理是什么？");
        assertThat(askResult.at("/data/answer").asText()).isNotBlank();
        assertThat(askResult.at("/data/requestId").asText()).isNotBlank();
        JsonNode references = askResult.at("/data/referencedKnowledgeIds");
        assertThat(references.isArray()).isTrue();
        assertThat(references.size()).isPositive();
    }

    @Test
    void askWithoutTokenReturnsUnauthorized() throws Exception {
        JsonNode result = post("/api/qa/ask", null, Map.of("question", "任意问题"));
        assertCode(result, 40100);
    }

    @Test
    void askWithInvalidQuestionReturnsParamError() throws Exception {
        String username = "qa_invalid_" + System.nanoTime();
        register(username, "123456");
        String token = login(username, "123456");

        JsonNode result = post("/api/qa/ask", token, Map.of("question", " "));
        assertCode(result, 40000);
    }

    @Test
    void knowledgeImportRequiresLogin() throws Exception {
        JsonNode result = post("/api/knowledge/import", null, Map.of());
        assertCode(result, 40100);
    }

    private void register(String username, String password) throws Exception {
        JsonNode result = post("/api/auth/register", null, Map.of("username", username, "password", password));
        assertCode(result, 0);
    }

    private String login(String username, String password) throws Exception {
        JsonNode result = post("/api/auth/login", null, Map.of("username", username, "password", password));
        assertCode(result, 0);
        return result.at("/data/token").asText();
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
