package com.offerforge.interview;

import com.offerforge.ai.AnswerEvaluation;

/**
 * 单轮问答完成事件（SSE done 事件载荷）：评分、转移动作、最新进度视图与详细评估。
 * CLOSING 等无评分轮次 score/action 为 null；开场环节训练模式携带评分（仅展示不入报告），实战模式免评分；
 * 实战模式过程免评分，知识题 score/evaluationComment/evaluation 均为 null（评分仍完整入库供结束报告使用）。
 */
public record InterviewTurnResult(
        Double score,
        String evaluationComment,
        StateTransitionStrategy.Action action,
        InterviewStatusResponse status,
        AnswerEvaluation evaluation
) {
    /** 便利构造：不携带详细评估（兼容既有调用） */
    public InterviewTurnResult(Double score, String evaluationComment,
                               StateTransitionStrategy.Action action, InterviewStatusResponse status) {
        this(score, evaluationComment, action, status, null);
    }
}
