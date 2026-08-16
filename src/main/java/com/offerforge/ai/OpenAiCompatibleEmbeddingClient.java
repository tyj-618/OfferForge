package com.offerforge.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 /embeddings 客户端（百炼 text-embedding-v4）。
 * 凭证只通过环境变量注入，不写入代码库。
 */
@Component
@Conditional(AiClientConditions.OpenAiCompatibleEmbedding.class)
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private final SearchProperties properties;
    private final RestClient restClient;

    public OpenAiCompatibleEmbeddingClient(SearchProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(require(properties.getEmbeddingBaseUrl(), "OFFERFORGE_SEARCH_EMBEDDING_BASE_URL"))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + require(
                        properties.getEmbeddingApiKey(), "OFFERFORGE_SEARCH_EMBEDDING_API_KEY"))
                .requestFactory(requestFactory(properties.getEmbeddingTimeoutSeconds()))
                .build();
    }

    @Override
    public List<Float> embed(String text) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", require(properties.getEmbeddingModel(), "OFFERFORGE_SEARCH_EMBEDDING_MODEL"));
        request.put("input", text == null ? "" : text);
        request.put("encoding_format", "float");

        JsonNode response = restClient.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
        JsonNode values = response == null ? null : response.path("data").path(0).path("embedding");
        if (values == null || !values.isArray()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Embedding 服务返回格式异常");
        }

        List<Float> vector = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            vector.add(value.floatValue());
        }
        if (vector.size() != properties.getEmbeddingDimensions()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "Embedding 维度与 OFFERFORGE_SEARCH_EMBEDDING_DIMENSIONS 配置不一致");
        }
        return vector;
    }

    private static String require(String value, String variableName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(variableName + " must be configured when using openai-compatible embeddings");
        }
        return value.trim();
    }

    private static SimpleClientHttpRequestFactory requestFactory(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return factory;
    }
}
