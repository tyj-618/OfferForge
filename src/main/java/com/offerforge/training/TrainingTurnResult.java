package com.offerforge.training;

import com.offerforge.ai.AnswerEvaluation;

/**
 * 专项训练单轮完成事件（SSE done 载荷）：与面试契约同构——评分/点评/进度视图/详细评估；
 * finished 标记本场是否已完成归档。
 */
public record TrainingTurnResult(
        Double score,
        String evaluationComment,
        boolean finished,
        TrainingStatusResponse status,
        AnswerEvaluation evaluation
) {
}
