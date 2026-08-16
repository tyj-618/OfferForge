package com.offerforge.apikey;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API Key 加密配置：密钥取自环境变量（32 字节 UTF-8，即 AES-256）。
 */
@ConfigurationProperties(prefix = "offerforge.encryption")
public class EncryptionProperties {

    private String key = "";

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
}
