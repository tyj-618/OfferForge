package com.offerforge.ai;

import com.offerforge.apikey.ApiKeyService;
import com.offerforge.billing.BillingProperties;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 调用链路分支：有自带 Key 返回用户凭据；未配置回退系统配置（null）；
 * 计费模式模型覆写：无自带 Key 且指定模型时返回系统 Key + 所选模型；
 * DeepSeek 目录模型走独立端点凭据，未配置 Key 时回退系统端点。
 */
class LlmCredentialResolverTest {

    private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
    private final AiProperties aiProperties = new AiProperties();
    private final BillingProperties billingProperties = new BillingProperties();
    private final LlmCredentialResolver resolver =
            new LlmCredentialResolver(apiKeyService, aiProperties, billingProperties);

    @Test
    void resolvesUserCredentialsWhenKeyConfigured() {
        when(apiKeyService.getKey(1L)).thenReturn(Optional.of(new ApiKeyService.DecryptedApiKey(
                "QIANWEN", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "sk-user")));

        LlmCredentials credentials = resolver.resolveFor(1L);

        assertThat(credentials).isNotNull();
        assertThat(credentials.apiKey()).isEqualTo("sk-user");
        assertThat(credentials.baseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(credentials.model()).isEqualTo("qwen-plus");
    }

    @Test
    void returnsNullForSystemConfigWhenNoKeyConfigured() {
        when(apiKeyService.getKey(2L)).thenReturn(Optional.empty());

        assertThat(resolver.resolveFor(2L)).isNull();
    }

    @Test
    void modelOverrideUsesSystemKeyWithSelectedModel() {
        aiProperties.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        aiProperties.setApiKey("sk-system");
        when(apiKeyService.getKey(3L)).thenReturn(Optional.empty());

        LlmCredentials credentials = resolver.resolveFor(3L, "qwen-max");

        assertThat(credentials).isNotNull();
        assertThat(credentials.apiKey()).isEqualTo("sk-system");
        assertThat(credentials.baseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(credentials.model()).isEqualTo("qwen-max");
    }

    @Test
    void userKeyTakesPrecedenceOverModelOverride() {
        when(apiKeyService.getKey(4L)).thenReturn(Optional.of(new ApiKeyService.DecryptedApiKey(
                "QIANWEN", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "sk-user")));

        // 自带 Key 用户所选模型忽略：用户 Key 自带模型与额度
        LlmCredentials credentials = resolver.resolveFor(4L, "qwen-max");

        assertThat(credentials.apiKey()).isEqualTo("sk-user");
        assertThat(credentials.model()).isEqualTo("qwen-plus");
    }

    @Test
    void deepseekCatalogModelUsesDeepseekEndpointCredentials() {
        aiProperties.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        aiProperties.setApiKey("sk-system");
        aiProperties.getDeepseek().setBaseUrl("https://api.deepseek.com");
        aiProperties.getDeepseek().setApiKey("sk-deepseek");
        BillingProperties.ModelConfig deepseekModel = new BillingProperties.ModelConfig();
        deepseekModel.setId("deepseek-v4-flash");
        deepseekModel.setProvider("deepseek");
        billingProperties.setModels(java.util.List.of(deepseekModel));
        when(apiKeyService.getKey(5L)).thenReturn(Optional.empty());

        LlmCredentials credentials = resolver.resolveFor(5L, "deepseek-v4-flash");

        assertThat(credentials).isNotNull();
        assertThat(credentials.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(credentials.apiKey()).isEqualTo("sk-deepseek");
        assertThat(credentials.model()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void deepseekModelFallsBackToSystemEndpointWhenKeyMissing() {
        aiProperties.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        aiProperties.setApiKey("sk-system");
        BillingProperties.ModelConfig deepseekModel = new BillingProperties.ModelConfig();
        deepseekModel.setId("deepseek-v4-flash");
        deepseekModel.setProvider("deepseek");
        billingProperties.setModels(java.util.List.of(deepseekModel));
        when(apiKeyService.getKey(6L)).thenReturn(Optional.empty());

        LlmCredentials credentials = resolver.resolveFor(6L, "deepseek-v4-flash");

        assertThat(credentials).isNotNull();
        assertThat(credentials.baseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(credentials.apiKey()).isEqualTo("sk-system");
    }
}
