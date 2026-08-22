package com.offerforge.ai;

import com.offerforge.apikey.ApiKeyService;
import org.springframework.stereotype.Component;

/**
 * LLM 凭据解析：有自带 Key 返回用户凭据（keySource=user），
 * 未配置返回 null（调用方回退系统配置）。
 * 计费模式可传模型覆写：系统 Key + 用户所选付费模型。
 */
@Component
public class LlmCredentialResolver {

    private final ApiKeyService apiKeyService;
    private final AiProperties aiProperties;

    public LlmCredentialResolver(ApiKeyService apiKeyService, AiProperties aiProperties) {
        this.apiKeyService = apiKeyService;
        this.aiProperties = aiProperties;
    }

    public LlmCredentials resolveFor(Long userId) {
        return resolveFor(userId, null);
    }

    /**
     * 带模型覆写的凭据解析：自带 Key 优先（所选模型忽略，用户 Key 自带模型）；
     * 无自带 Key 且覆写非空时返回系统 Key + 所选模型（付费计费模式），其余回退系统配置。
     */
    public LlmCredentials resolveFor(Long userId, String modelOverride) {
        LlmCredentials userKey = apiKeyService.getKey(userId)
                .map(key -> new LlmCredentials(key.baseUrl(), key.apiKey(), key.model()))
                .orElse(null);
        if (userKey != null) {
            return userKey;
        }
        if (modelOverride != null && !modelOverride.isBlank()) {
            return new LlmCredentials(aiProperties.getBaseUrl(), aiProperties.getApiKey(), modelOverride.trim());
        }
        return null;
    }
}
