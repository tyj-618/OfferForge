package com.offerforge.interview;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "offerforge.interview")
public class InterviewProperties {

    /** 同一道题最多追问次数（评分 < 4 时触发） */
    private int maxFollowUps = 2;
    /** BASICS 阶段最多出题数 */
    private int maxBasicsQuestions = 3;
    /** PROJECT 阶段最多出题数 */
    private int maxProjectQuestions = 2;
    /** DEEP 阶段最多出题数 */
    private int maxDeepQuestions = 3;
    /** 对话历史滑动窗口大小（条） */
    private int messageWindow = 12;
    /** 会话 TTL（秒），用于 Redis 存储过期，默认 30 分钟 */
    private long sessionTtlSeconds = 1800;

    public int maxQuestionsFor(InterviewState state) {
        return switch (state) {
            case BASICS -> maxBasicsQuestions;
            case PROJECT -> maxProjectQuestions;
            case DEEP -> maxDeepQuestions;
            default -> 0;
        };
    }

    public int plannedTotal() {
        return maxBasicsQuestions + maxProjectQuestions + maxDeepQuestions;
    }

    public int getMaxFollowUps() {
        return maxFollowUps;
    }

    public void setMaxFollowUps(int maxFollowUps) {
        this.maxFollowUps = maxFollowUps;
    }

    public int getMaxBasicsQuestions() {
        return maxBasicsQuestions;
    }

    public void setMaxBasicsQuestions(int maxBasicsQuestions) {
        this.maxBasicsQuestions = maxBasicsQuestions;
    }

    public int getMaxProjectQuestions() {
        return maxProjectQuestions;
    }

    public void setMaxProjectQuestions(int maxProjectQuestions) {
        this.maxProjectQuestions = maxProjectQuestions;
    }

    public int getMaxDeepQuestions() {
        return maxDeepQuestions;
    }

    public void setMaxDeepQuestions(int maxDeepQuestions) {
        this.maxDeepQuestions = maxDeepQuestions;
    }

    public int getMessageWindow() {
        return messageWindow;
    }

    public void setMessageWindow(int messageWindow) {
        this.messageWindow = messageWindow;
    }

    public long getSessionTtlSeconds() {
        return sessionTtlSeconds;
    }

    public void setSessionTtlSeconds(long sessionTtlSeconds) {
        this.sessionTtlSeconds = sessionTtlSeconds;
    }
}
