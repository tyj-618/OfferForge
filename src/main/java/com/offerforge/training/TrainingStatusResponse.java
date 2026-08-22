package com.offerforge.training;

import java.util.List;

/**
 * 专项训练进度视图（status 接口与 SSE done 帧共用）。
 * averageScore 为 0-10 均分；finished 后前端展示归档成绩。
 * archived 表示本场成绩已入历史记录：问答次数不足计次门槛的短场不归档也不消耗免费次数。
 * history 携带已作答回合的题面/回答/点评/得分/详细评估，供刷新后完整重建对话；
 * evaluating 为 true 表示服务端正在处理上一次作答，前端轮询至 false 后重建。
 */
public record TrainingStatusResponse(
        String sessionId,
        String category,
        String state,
        int askedCount,
        int maxQuestions,
        String currentDifficulty,
        String maxDifficultyReached,
        Double averageScore,
        boolean finished,
        boolean evaluating,
        /** 当前待作答的题目（刷新恢复用；完成后为 null） */
        String currentQuestion,
        /** 已作答回合完整记录（刷新恢复回放用） */
        List<TrainingQuestionRecord> history,
        /** 成绩已归档历史记录（短场不归档为 false） */
        boolean archived
) {
    public static TrainingStatusResponse from(TrainingContext context, TrainingProperties properties,
                                              int minBillableQuestions) {
        return new TrainingStatusResponse(
                context.getSessionId(),
                context.getCategory(),
                context.getState(),
                context.askedCount(),
                properties.getMaxQuestions(),
                context.getCurrentDifficulty() == null ? null : context.getCurrentDifficulty().name(),
                context.getMaxDifficultyReached() == null ? null : context.getMaxDifficultyReached().name(),
                context.askedCount() == 0 ? null : round(context.averageScore()),
                context.isFinished(),
                context.isEvaluating(),
                context.isFinished() ? null : context.getCurrentQuestion(),
                List.copyOf(context.getQuestionHistory()),
                context.isFinished() && context.askedCount() >= minBillableQuestions);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
