package com.offerforge.report;

import com.offerforge.interview.InterviewState;

/**
 * 逐题评估视图：报告中「逐题点评」折叠面板的数据条目。
 */
public record QuestionEvaluation(
        int questionIndex,
        String question,
        String userAnswer,
        double score,
        InterviewState state,
        boolean followUp,
        String feedback
) {
}
