package com.offerforge.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "offerforge.ai")
public class AiProperties {

    private String provider = "mock";
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "";
    private int timeoutSeconds = 30;
    private int streamReadTimeoutSeconds = 60;
    private int maxRetries = 1;
    private int maxOutputTokens = 1024;
    /** DeepSeek 官方接口独立端点：价目目录中 provider=deepseek 的模型走此凭据 */
    private DeepSeek deepseek = new DeepSeek();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getStreamReadTimeoutSeconds() {
        return streamReadTimeoutSeconds;
    }

    public void setStreamReadTimeoutSeconds(int streamReadTimeoutSeconds) {
        this.streamReadTimeoutSeconds = streamReadTimeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public DeepSeek getDeepseek() {
        return deepseek;
    }

    public void setDeepseek(DeepSeek deepseek) {
        this.deepseek = deepseek;
    }

    /** DeepSeek 官方 OpenAI 兼容接口配置（base-url + 独立 API Key） */
    public static class DeepSeek {
        private String baseUrl = "";
        private String apiKey = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }
}
