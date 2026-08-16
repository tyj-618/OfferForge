package com.offerforge.quota;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 免费额度配置：每日完整面试次数上限；enabled=false 时不限量（如本地开发）。
 */
@ConfigurationProperties(prefix = "offerforge.quota")
public class QuotaProperties {

    private boolean enabled = true;
    private int dailyLimit = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDailyLimit() {
        return dailyLimit;
    }

    public void setDailyLimit(int dailyLimit) {
        this.dailyLimit = dailyLimit;
    }
}
