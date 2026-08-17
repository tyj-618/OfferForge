package com.offerforge.exception;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 未映射 API 路径集成测试：统一返回 HTTP 404 + code=40400，
 * 而不是被全局兜底捕获返回 HTTP 500 + code=50000。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnmappedApiPathIntegrationTests {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 已下线的追问端点（由深度训练子流程取代）应返回 404 而非 500 */
    @Test
    void removedFollowupEndpointReturns404() throws Exception {
        HttpResponse<String> response = post("/api/interview/nonexist/followup");

        assertThat(response.statusCode()).isEqualTo(404);
        assertNotFoundBody(response);
    }

    @Test
    void unknownPostPathReturns404() throws Exception {
        HttpResponse<String> response = post("/api/not-exist-module/action");

        assertThat(response.statusCode()).isEqualTo(404);
        assertNotFoundBody(response);
    }

    @Test
    void unknownGetPathReturns404() throws Exception {
        HttpResponse<String> response = get("/api/nothing/here");

        assertThat(response.statusCode()).isEqualTo(404);
        assertNotFoundBody(response);
    }

    private void assertNotFoundBody(HttpResponse<String> response) throws Exception {
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.at("/code").asInt()).isEqualTo(40400);
        assertThat(body.at("/message").asText()).isEqualTo("资源不存在");
    }

    private HttpResponse<String> post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
