package com.offerforge.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "offerforge.search")
public class SearchProperties {

    private boolean enabled = false;
    private String baseUrl = "http://localhost:9201";
    private String knowledgeIndex = "offerforge-knowledge";
    private int requestTimeoutSeconds = 5;
    private int embeddingDimensions = 1024;
    private String embeddingProvider = "mock";
    private String embeddingBaseUrl = "";
    private String embeddingApiKey = "";
    private String embeddingModel = "text-embedding-v4";
    private int embeddingTimeoutSeconds = 20;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getKnowledgeIndex() {
        return knowledgeIndex;
    }

    public void setKnowledgeIndex(String knowledgeIndex) {
        this.knowledgeIndex = knowledgeIndex;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getEmbeddingDimensions() {
        return embeddingDimensions;
    }

    public void setEmbeddingDimensions(int embeddingDimensions) {
        this.embeddingDimensions = embeddingDimensions;
    }

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public void setEmbeddingProvider(String embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public String getEmbeddingBaseUrl() {
        return embeddingBaseUrl;
    }

    public void setEmbeddingBaseUrl(String embeddingBaseUrl) {
        this.embeddingBaseUrl = embeddingBaseUrl;
    }

    public String getEmbeddingApiKey() {
        return embeddingApiKey;
    }

    public void setEmbeddingApiKey(String embeddingApiKey) {
        this.embeddingApiKey = embeddingApiKey;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public int getEmbeddingTimeoutSeconds() {
        return embeddingTimeoutSeconds;
    }

    public void setEmbeddingTimeoutSeconds(int embeddingTimeoutSeconds) {
        this.embeddingTimeoutSeconds = embeddingTimeoutSeconds;
    }
}
