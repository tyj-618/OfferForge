package com.offerforge.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 腾讯云 SES 配置：secret-id 为空时视为未启用（降级 Mock 发信）。
 * 未开通「自定义发送」权限的账号必须使用模板发送（template-id 配置后启用）。
 */
@ConfigurationProperties(prefix = "tencent.ses")
public class TencentSesProperties {

    private final String secretId;
    private final String secretKey;
    private final String region;
    private final String fromEmail;
    private final Long templateId;

    public TencentSesProperties(String secretId, String secretKey, String region, String fromEmail,
                                Long templateId) {
        this.secretId = secretId == null ? "" : secretId.trim();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.region = region == null || region.isBlank() ? "ap-guangzhou" : region.trim();
        this.fromEmail = fromEmail == null ? "" : fromEmail.trim();
        this.templateId = templateId;
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

    public Long getTemplateId() {
        return templateId;
    }
}
