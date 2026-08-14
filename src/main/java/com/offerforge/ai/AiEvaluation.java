package com.offerforge.ai;

/**
 * 单轮回答的评分结果，score 取值 0-10，驱动面试状态机转移。
 */
public record AiEvaluation(int score, String comment) {
}
