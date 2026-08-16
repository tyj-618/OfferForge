package com.offerforge.ai;

import com.offerforge.apikey.ApiKeyService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 调用链路分支：有自带 Key 返回用户凭据；未配置回退系统配置（null）。
 */
class LlmCredentialResolverTest {

    private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
    private final LlmCredentialResolver resolver = new LlmCredentialResolver(apiKeyService);

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
}
