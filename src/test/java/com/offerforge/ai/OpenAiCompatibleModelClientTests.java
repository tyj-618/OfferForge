package com.offerforge.ai;

import com.offerforge.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleModelClientTests {

    @Test
    void requestBodyContainsModelMessagesAndSamplingParameters() {
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(configuredProperties());

        Map<String, Object> body = client.buildRequestBody(List.of(
                ChatMessage.system("system prompt"),
                ChatMessage.user("user question")
        ));

        assertThat(body.get("model")).isEqualTo("qwen-plus");
        assertThat(body.get("temperature")).isEqualTo(0.2);
        assertThat(body.get("max_tokens")).isEqualTo(1024);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).containsEntry("role", "system").containsEntry("content", "system prompt");
        assertThat(messages.get(1)).containsEntry("role", "user").containsEntry("content", "user question");
    }

    @Test
    void generateTextRejectsUnconfiguredClient() {
        AiProperties properties = new AiProperties();
        properties.setProvider("openai-compatible");
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(properties);

        assertThatThrownBy(() -> client.generateText(List.of(ChatMessage.user("hello"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未完成配置");
    }

    private AiProperties configuredProperties() {
        AiProperties properties = new AiProperties();
        properties.setProvider("openai-compatible");
        properties.setBaseUrl("https://example.invalid");
        properties.setApiKey("sk-test");
        properties.setModel("qwen-plus");
        return properties;
    }
}
