package com.offerforge.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 健康检查端到端测试（test profile：H2 充当 MySQL、redis 未启用、ES 关闭、mock LLM）。
 * 预期：整体 UP；mysql/llm UP，redis/elasticsearch DISABLED（未启用不影响整体状态）。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthIntegrationTests {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void healthEndpointIsPublicAndAggregatesComponentStatus() throws Exception {
        // 免鉴权（供 docker healthcheck 直接调用）
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/health"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.at("/status").asText()).isEqualTo("UP");

        assertThat(body.at("/components/mysql/status").asText()).isEqualTo("UP");
        assertThat(body.at("/components/mysql/latencyMs").asLong()).isGreaterThanOrEqualTo(0);

        // 未启用的组件报 DISABLED，不参与整体状态计算
        assertThat(body.at("/components/redis/status").asText()).isEqualTo("DISABLED");
        assertThat(body.at("/components/elasticsearch/status").asText()).isEqualTo("DISABLED");

        assertThat(body.at("/components/llm/status").asText()).isEqualTo("UP");
    }
}
