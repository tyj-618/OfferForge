package com.offerforge.ai;

public record AiTextResult(String content, String requestId, Integer inputTokens, Integer outputTokens) {
}
