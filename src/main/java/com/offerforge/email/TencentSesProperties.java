package com.offerforge.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 腾讯云 SES 配置：secret-id 为空时视为未启用（降级 Mock 发信）。
 */
@ConfigurationProperties(prefix = "tencent.ses")
public class TencentSesProperties {

    private final String secretId;
    private final String secretKey;
    private final String region;
    private final String fromEmail;

    public TencentSesProperties(String secretId, String secretKey, String region, String fromEmail) {
        this.secretId = secretId == null ? "" : secretId.trim();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.region = region == null || region.isBlank() ? "ap-guangzhou" : region.trim();
        this.fromEmail = fromEmail == null ? "" : fromEmail.trim();
    }

    public boolean isEnabled() {
        return !secretId.isEmpty() && !secretKey.isEmpty() && !fromEmail.isEmpty();
    }

    public String getSecretId() {
        return secretId;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getRegion() {
        return region;
    }

    public String getFromEmail() {
        return fromEmail;
    }
}
