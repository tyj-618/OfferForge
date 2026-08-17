package com.offerforge.training;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 专项训练配置（任务 7）：单分组由浅入深训练会话参数。
 */
@ConfigurationProperties(prefix = "offerforge.training")
public class TrainingProperties {

    /** 单场专项训练最多出题数：达标题数完成训练并归档 */
    private int maxQuestions = 6;
    /** 会话 TTL（秒）：长 TTL 支持刷新/暂离后恢复，默认 24 小时 */
    private long sessionTtlSeconds = 86400;

    public int getMaxQuestions() {
        return maxQuestions;
    }

    public void setMaxQuestions(int maxQuestions) {
        this.maxQuestions = maxQuestions;
    }

    public long getSessionTtlSeconds() {
        return sessionTtlSeconds;
    }

    public void setSessionTtlSeconds(long sessionTtlSeconds) {
        this.sessionTtlSeconds = sessionTtlSeconds;
    }
}
