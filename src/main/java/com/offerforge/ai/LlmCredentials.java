package com.offerforge.ai;

/**
 * LLM 调用凭据（用户自带 Key）：为空表示使用系统配置。
 */
public record LlmCredentials(String baseUrl, String apiKey, String model) {
}
