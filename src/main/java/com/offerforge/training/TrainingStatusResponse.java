package com.offerforge.training;

/**
 * 专项训练进度视图（status 接口与 SSE done 帧共用）。
 * averageScore 为 0-10 均分；finished 后前端展示归档成绩。
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
        /** 当前待作答的题目（刷新恢复用；完成后为 null） */
        String currentQuestion
) {
    public static TrainingStatusResponse from(TrainingContext context, TrainingProperties properties) {
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
                context.isFinished() ? null : context.getCurrentQuestion());
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
