package com.offerforge.ai;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Locale;

/**
 * Provider 条件基类：对属性值做归一化（去空白、转小写、下划线转连字符）后比较，
 * 兼容 openai-compatible / openai_compatible / OPENAI_COMPATIBLE 等写法，
 * 避免 @ConditionalOnProperty 字面比较因写法差异导致 AiModelClient/EmbeddingClient bean 缺失而启动失败。
 */
public abstract class ProviderValueCondition implements Condition {

    private final String property;
    private final String expectedValue;
    private final boolean matchIfMissing;

    protected ProviderValueCondition(String property, String expectedValue, boolean matchIfMissing) {
        this.property = property;
        this.expectedValue = expectedValue;
        this.matchIfMissing = matchIfMissing;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String raw = context.getEnvironment().getProperty(property);
        if (raw == null || raw.isBlank()) {
            return matchIfMissing;
        }
        return normalize(raw).equals(expectedValue);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
