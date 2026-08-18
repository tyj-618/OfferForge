package com.offerforge.knowledge;

import com.offerforge.ai.SearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class KnowledgeSearchTests {

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private KnowledgeRepository knowledgeRepository;

    @BeforeEach
    void setUp() {
        knowledgeRepository.deleteAll();
        knowledgeService.importBuiltinKnowledge();
    }

    @Test
    void keywordFallbackFindsHashMapQuestion() {
        List<RetrievedKnowledge> results = knowledgeService.search(1L, "HashMap 的底层原理是什么", 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).question()).contains("HashMap");
        assertThat(results.get(0).answer()).isNotBlank();
        assertThat(results.get(0).itemId()).isNotNull();
    }

    @Test
    void keywordFallbackFindsCacheAvalancheQuestion() {
        List<RetrievedKnowledge> results = knowledgeService.search(1L, "缓存雪崩怎么解决", 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).question()).contains("缓存");
    }

    @Test
    void blankQueryReturnsEmpty() {
        assertThat(knowledgeService.search(1L, "   ", 5)).isEmpty();
        assertThat(knowledgeService.search(1L, null, 5)).isEmpty();
    }

    @Test
    void searchRespectsLimit() {
        List<RetrievedKnowledge> results = knowledgeService.search(1L, "线程", 3);

        assertThat(results.size()).isLessThanOrEqualTo(3);
    }

    @Test
    void esDocumentBodyContainsRequiredFields() {
        SearchProperties properties = new SearchProperties();
        properties.setBaseUrl("http://localhost:9201");
        properties.setKnowledgeIndex("offerforge-knowledge-test");
        properties.setEmbeddingDimensions(8);
        KnowledgeIndexClient client = new KnowledgeIndexClient(properties);

        KnowledgeItem item = new KnowledgeItem();
        item.setId(7L);
        item.setQuestion("测试题目");
        item.setAnswer("测试答案");
        item.setCategory("Java基础");
        item.setTags("tag1,tag2");

        Map<String, Object> body = client.buildDocumentBody(item, List.of(0.1f, 0.2f));

        assertThat(body.get("id")).isEqualTo(7L);
        assertThat(body.get("question")).isEqualTo("测试题目");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) body.get("tags");
        assertThat(tags).containsExactly("tag1", "tag2");
        assertThat(body.get("embedding")).isEqualTo(List.of(0.1f, 0.2f));
    }

    @Test
    void esIndexDefinitionUsesDenseVectorWithConfiguredDimensions() {
        SearchProperties properties = new SearchProperties();
        properties.setEmbeddingDimensions(64);
        KnowledgeIndexClient client = new KnowledgeIndexClient(properties);

        Map<String, Object> definition = client.indexDefinition();

        @SuppressWarnings("unchecked")
        Map<String, Object> mappings = (Map<String, Object>) definition.get("mappings");
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) mappings.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> embedding = (Map<String, Object>) fields.get("embedding");
        assertThat(embedding.get("type")).isEqualTo("dense_vector");
        assertThat(embedding.get("dims")).isEqualTo(64);
        assertThat(embedding.get("similarity")).isEqualTo("cosine");
    }
}
