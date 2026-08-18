package com.offerforge.training;

/** 开始专项训练请求：category 为资料分组名（官方或本人私有）；style 为助手语气风格（strict/friendly，缺省 friendly） */
public record TrainingStartRequest(String category, String style) {
}
