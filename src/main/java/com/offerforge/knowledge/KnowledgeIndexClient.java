package com.offerforge.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.offerforge.ai.SearchProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库 ES 向量索引适配器。MySQL 仍是事实源，ES 只提供候选召回。
 */
@Component
@ConditionalOnProperty(prefix = "offerforge.search", name = "enabled", havingValue = "true")
public class KnowledgeIndexClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexClient.class);

    private final SearchProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private volatile boolean initialized;

    public KnowledgeIndexClient(SearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory(properties.getRequestTimeoutSeconds()))
                .build();
    }

    public void upsert(KnowledgeItem item, List<Float> embedding) {
        ensureIndex();
        restClient.put()
                .uri("/{index}/_doc/{itemId}", properties.getKnowledgeIndex(), item.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(buildDocumentBody(item, embedding))
                .retrieve()
                .toBodilessEntity();
    }

    public List<Long> searchByVector(List<Float> vector, int limit) {
        if (vector == null || vector.isEmpty()) {
            return List.of();
        }
        Map<String, Object> knn = new LinkedHashMap<>();
        knn.put("field", "embedding");
        knn.put("query_vector", vector);
        knn.put("k", limit);
        knn.put("num_candidates", Math.max(limit * 3, limit));
        return executeSearch(Map.of("size", limit, "_source", List.of("id"), "knn", knn));
    }

    public List<Long> searchByKeyword(String question, int limit) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Map<String, Object> multiMatch = Map.of(
                "query", question,
                "fields", List.of("question^3", "tags^2", "category", "answer")
        );
        return executeSearch(Map.of(
                "size", limit,
                "_source", List.of("id"),
                "query", Map.of("multi_match", multiMatch)
        ));
    }

    public void ping() {
        restClient.get().uri("/").retrieve().toBodilessEntity();
    }

    Map<String, Object> buildDocumentBody(KnowledgeItem item, List<Float> embedding) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", item.getId());
        body.put("question", item.getQuestion());
        body.put("answer", item.getAnswer());
        body.put("category", item.getCategory());
        body.put("tags", item.getTags() == null ? List.of()
                : Arrays.stream(item.getTags().split(",")).map(String::trim).filter(tag -> !tag.isEmpty()).toList());
        body.put("createdAt", item.getCreatedAt() == null ? 0L : item.getCreatedAt().toEpochMilli());
        body.put("embedding", embedding);
        return body;
    }

    Map<String, Object> indexDefinition() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", Map.of("type", "long"));
        fields.put("question", Map.of("type", "text"));
        fields.put("answer", Map.of("type", "text"));
        fields.put("category", Map.of("type", "keyword"));
        fields.put("tags", Map.of("type", "keyword"));
        fields.put("createdAt", Map.of("type", "date"));
        fields.put("embedding", Map.of(
                "type", "dense_vector",
                "dims", properties.getEmbeddingDimensions(),
                "index", true,
                "similarity", "cosine"
        ));
        return Map.of(
                "settings", Map.of("index", Map.of("number_of_shards", 1, "number_of_replicas", 0)),
                "mappings", Map.of("dynamic", "strict", "properties", fields)
        );
    }

    private List<Long> executeSearch(Map<String, Object> body) {
        ensureIndex();
        JsonNode response = restClient.post()
                .uri("/{index}/_search", properties.getKnowledgeIndex())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            return List.of();
        }
        List<Long> itemIds = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode id = hit.path("_source").path("id");
            if (id.canConvertToLong()) {
                itemIds.add(id.longValue());
            }
        }
        return itemIds;
    }

    private void ensureIndex() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            try {
                restClient.get().uri("/{index}/_mapping", properties.getKnowledgeIndex()).retrieve().toBodilessEntity();
                initialized = true;
                return;
            } catch (RestClientResponseException exception) {
                if (exception.getStatusCode().value() != 404) {
                    throw exception;
                }
            }

            restClient.put()
                    .uri("/{index}", properties.getKnowledgeIndex())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(indexDefinition())
                    .retrieve()
                    .toBodilessEntity();
            initialized = true;
            log.info("Created Elasticsearch knowledge index {}", properties.getKnowledgeIndex());
        }
    }

    private static SimpleClientHttpRequestFactory requestFactory(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 1));
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return factory;
    }
}
