package com.offerforge.ai;

/**
 * AI/Embedding provider 装配条件：
 * mock 为缺省实现（属性缺失时生效），openai-compatible 兼容连字符与下划线两种写法。
 */
public final class AiClientConditions {

    private AiClientConditions() {
    }

    public static class MockAiModel extends ProviderValueCondition {
        public MockAiModel() {
            super("offerforge.ai.provider", "mock", true);
        }
    }

    public static class OpenAiCompatibleAiModel extends ProviderValueCondition {
        public OpenAiCompatibleAiModel() {
            super("offerforge.ai.provider", "openai-compatible", false);
        }
    }

    public static class MockEmbedding extends ProviderValueCondition {
        public MockEmbedding() {
            super("offerforge.search.embedding-provider", "mock", true);
        }
    }

    public static class OpenAiCompatibleEmbedding extends ProviderValueCondition {
        public OpenAiCompatibleEmbedding() {
            super("offerforge.search.embedding-provider", "openai-compatible", false);
        }
    }
}
