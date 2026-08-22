package com.offerforge.ai;

import com.offerforge.apikey.ApiKeyService;
import com.offerforge.billing.BillingProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 凭据解析：有自带 Key 返回用户凭据（keySource=user），
 * 未配置返回 null（调用方回退系统配置）。
 * 计费模式可传模型覆写：系统 Key + 用户所选付费模型；
 * 价目目录中 provider=deepseek 的模型改走 DeepSeek 官方端点凭据。
 */
@Component
public class LlmCredentialResolver {

    private static final String PROVIDER_DEEPSEEK = "deepseek";

    private final ApiKeyService apiKeyService;
    private final AiProperties aiProperties;
    private final BillingProperties billingProperties;

    public LlmCredentialResolver(ApiKeyService apiKeyService, AiProperties aiProperties,
                                 BillingProperties billingProperties) {
        this.apiKeyService = apiKeyService;
        this.aiProperties = aiProperties;
        this.billingProperties = billingProperties;
    }

    public LlmCredentials resolveFor(Long userId) {
        return resolveFor(userId, null);
    }

    /**
     * 带模型覆写的凭据解析：自带 Key 优先（所选模型忽略，用户 Key 自带模型）；
     * 无自带 Key 且覆写非空时返回系统 Key + 所选模型（付费计费模式），其余回退系统配置。
     * DeepSeek 目录模型使用独立端点凭据；未配置 DeepSeek Key 时回退系统端点兼容降级。
     */
    public LlmCredentials resolveFor(Long userId, String modelOverride) {
        LlmCredentials userKey = apiKeyService.getKey(userId)
                .map(key -> new LlmCredentials(key.baseUrl(), key.apiKey(), key.model()))
                .orElse(null);
        if (userKey != null) {
            return userKey;
        }
        if (modelOverride != null && !modelOverride.isBlank()) {
            String model = modelOverride.trim();
            if (isDeepSeekModel(model)) {
                AiProperties.DeepSeek deepseek = aiProperties.getDeepseek();
                if (deepseek.getApiKey() != null && !deepseek.getApiKey().isBlank()) {
                    return new LlmCredentials(deepseek.getBaseUrl(), deepseek.getApiKey(), model);
                }
            }
            return new LlmCredentials(aiProperties.getBaseUrl(), aiProperties.getApiKey(), model);
        }
        return null;
    }

    /** 模型是否属于 DeepSeek 官方端点：按价目目录 provider 字段认定 */
    private boolean isDeepSeekModel(String model) {
        return billingProperties.getModels().stream()
                .anyMatch(entry -> model.equals(entry.getId())
                        && PROVIDER_DEEPSEEK.equalsIgnoreCase(entry.getProvider()));
    }
}
