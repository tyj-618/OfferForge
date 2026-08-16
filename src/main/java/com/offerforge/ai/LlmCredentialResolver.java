package com.offerforge.ai;

import com.offerforge.apikey.ApiKeyService;
import org.springframework.stereotype.Component;

/**
 * LLM 凭据解析：有自带 Key 返回用户凭据（keySource=user），
 * 未配置返回 null（调用方回退系统配置）。
 */
@Component
public class LlmCredentialResolver {

    private final ApiKeyService apiKeyService;

    public LlmCredentialResolver(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    public LlmCredentials resolveFor(Long userId) {
        return apiKeyService.getKey(userId)
                .map(key -> new LlmCredentials(key.baseUrl(), key.apiKey(), key.model()))
                .orElse(null);
    }
}
