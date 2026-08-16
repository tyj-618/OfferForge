package com.offerforge.ai;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Provider 装配条件回归测试：
 * 锁定 openai-compatible / openai_compatible 写法差异曾导致 AiModelClient bean 缺失、容器启动失败的问题。
 */
class AiClientConditionsTests {

    private ConditionContext contextWith(String property, String value) {
        MockEnvironment environment = new MockEnvironment();
        if (value != null) {
            environment.setProperty(property, value);
        }
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        return context;
    }

    private boolean matches(ProviderValueCondition condition, String property, String value) {
        return condition.matches(contextWith(property, value), mock(AnnotatedTypeMetadata.class));
    }

    @Test
    void aiModelProviderMatchesBothNotations() {
        ProviderValueCondition condition = new AiClientConditions.OpenAiCompatibleAiModel();
        assertThat(matches(condition, "offerforge.ai.provider", "openai-compatible")).isTrue();
        assertThat(matches(condition, "offerforge.ai.provider", "openai_compatible")).isTrue();
        assertThat(matches(condition, "offerforge.ai.provider", "OPENAI_COMPATIBLE")).isTrue();
        assertThat(matches(condition, "offerforge.ai.provider", "mock")).isFalse();
        assertThat(matches(condition, "offerforge.ai.provider", null)).isFalse();
    }

    @Test
    void aiModelProviderDefaultsToMockWhenMissing() {
        ProviderValueCondition condition = new AiClientConditions.MockAiModel();
        assertThat(matches(condition, "offerforge.ai.provider", null)).isTrue();
        assertThat(matches(condition, "offerforge.ai.provider", "  ")).isTrue();
        assertThat(matches(condition, "offerforge.ai.provider", "mock")).isTrue();
        assertThat(matches(condition, "offerforge.ai.provider", "openai-compatible")).isFalse();
        assertThat(matches(condition, "offerforge.ai.provider", "openai_compatible")).isFalse();
    }

    @Test
    void embeddingProviderMatchesBothNotations() {
        ProviderValueCondition condition = new AiClientConditions.OpenAiCompatibleEmbedding();
        assertThat(matches(condition, "offerforge.search.embedding-provider", "openai-compatible")).isTrue();
        assertThat(matches(condition, "offerforge.search.embedding-provider", "openai_compatible")).isTrue();
        assertThat(matches(condition, "offerforge.search.embedding-provider", "mock")).isFalse();

        ProviderValueCondition mockCondition = new AiClientConditions.MockEmbedding();
        assertThat(matches(mockCondition, "offerforge.search.embedding-provider", null)).isTrue();
        assertThat(matches(mockCondition, "offerforge.search.embedding-provider", "mock")).isTrue();
    }
}
