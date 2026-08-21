package com.offerforge.training;

/**
 * 开始专项训练请求：category 为资料分组名（官方或本人私有）；style 为助手语气风格（strict/friendly，缺省 friendly）；
 * fromInterview=true 表示由模拟面试「深入该模块」跳转而来（面试会话暂存续考，豁免跨模块互斥）。
 */
public record TrainingStartRequest(String category, String style, Boolean fromInterview) {
}
