package com.offerforge.training;

/** 开始专项训练响应：会话 id + 开场白（含首题）+ 当前进度视图 */
public record TrainingStartResponse(String sessionId, String openingMessage, TrainingStatusResponse status) {
}
