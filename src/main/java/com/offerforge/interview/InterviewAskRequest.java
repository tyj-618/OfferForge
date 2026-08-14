package com.offerforge.interview;

/**
 * ask 请求体；校验在控制器异步任务内手动执行（错误以 SSE error 事件返回，与 UniNook 流式接口一致）。
 */
public record InterviewAskRequest(String message) {
}
