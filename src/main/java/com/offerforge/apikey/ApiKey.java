package com.offerforge.apikey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 用户自带 LLM API Key：AES-256-GCM 加密存储，绝不明文落库。
 * 一个用户至多一条记录（user_id 唯一约束），保存即覆盖更新。
 * <p>敏感信息策略：日志只打印 userId/provider，绝不打印密文与明文。</p>
 */
@Entity
@Table(name = "api_key", uniqueConstraints = {
        @UniqueConstraint(name = "uk_api_key_user_id", columnNames = "user_id")
})
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Provider 标识：QIANWEN / OPENAI_COMPATIBLE */
    @Column(nullable = false, length = 32)
    private String provider;

    /** OpenAI 兼容接口的 Base URL */
    @Column(name = "base_url", nullable = false, length = 255)
    private String baseUrl;

    /** 模型名（如 qwen-plus） */
    @Column(nullable = false, length = 64)
    private String model;

    /** AES-256-GCM 密文：base64(iv || ciphertext || tag) */
    @Column(name = "encrypted_key", nullable = false, length = 512)
    private String encryptedKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getEncryptedKey() {
        return encryptedKey;
    }

    public void setEncryptedKey(String encryptedKey) {
        this.encryptedKey = encryptedKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
