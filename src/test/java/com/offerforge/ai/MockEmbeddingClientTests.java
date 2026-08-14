package com.offerforge.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockEmbeddingClientTests {

    private final SearchProperties properties = propertiesWithDimensions(64);
    private final MockEmbeddingClient client = new MockEmbeddingClient(properties);

    @Test
    void embeddingIsDeterministicAndMatchesConfiguredDimensions() {
        List<Float> first = client.embed("HashMap 底层原理");
        List<Float> second = client.embed("HashMap 底层原理");

        assertThat(first).hasSize(64);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void similarTextsHaveHigherCosineSimilarityThanUnrelatedTexts() {
        List<Float> base = client.embed("HashMap 的底层原理是什么");
        List<Float> similar = client.embed("HashMap 底层实现原理");
        List<Float> unrelated = client.embed("TCP 三次握手的过程");

        assertThat(cosine(base, similar)).isGreaterThan(cosine(base, unrelated));
    }

    @Test
    void blankTextStillProducesNormalizedVector() {
        List<Float> vector = client.embed("   ");

        assertThat(vector).hasSize(64);
        assertThat(vector.get(0)).isEqualTo(1.0f);
    }

    private double cosine(List<Float> a, List<Float> b) {
        double dot = 0.0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
        }
        return dot; // 向量已归一化，点积即余弦相似度
    }

    private SearchProperties propertiesWithDimensions(int dimensions) {
        SearchProperties searchProperties = new SearchProperties();
        searchProperties.setEmbeddingDimensions(dimensions);
        return searchProperties;
    }
}
