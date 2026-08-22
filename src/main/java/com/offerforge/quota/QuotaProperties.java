package com.offerforge.quota;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 免费额度配置：每日完整面试次数上限；enabled=false 时不限量（如本地开发）。
 * minBillableQuestions 为有效场次计次门槛：问答次数不足该值的场次不消耗免费额度且不记录历史。
 */
@ConfigurationProperties(prefix = "offerforge.quota")
public class QuotaProperties {

    private boolean enabled = true;
    private int dailyLimit = 10;
    private int minBillableQuestions = 5;

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

    public int getMinBillableQuestions() {
        return minBillableQuestions;
    }

    public void setMinBillableQuestions(int minBillableQuestions) {
        this.minBillableQuestions = minBillableQuestions;
    }
}
