package com.offerforge.interview;

/**
 * 单轮问答完成事件（SSE done 事件载荷）：评分、转移动作与最新进度视图。
 * OPENING/CLOSING 等无评分轮次 score/action 为 null。
 */
public record InterviewTurnResult(
        Double score,
        String evaluationComment,
        StateTransitionStrategy.Action action,
        InterviewStatusResponse status
) {
}
