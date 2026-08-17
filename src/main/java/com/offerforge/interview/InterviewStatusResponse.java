package com.offerforge.interview;

/**
 * 面试进度视图，供前端展示当前阶段、已问/剩余题数、追问进度、当前难度与深度训练状态。
 * 实战模式过程免评分：lastScore/averageScore 返回 null（评分仍入库供结束报告）。
 */
public record InterviewStatusResponse(
        String sessionId,
        InterviewState state,
        String phaseLabel,
        int askedCount,
        int plannedTotal,
        int remaining,
        String currentQuestion,
        int followUpsUsed,
        int followUpLimit,
        boolean currentQuestionFollowUp,
        String difficultyLabel,
        Double lastScore,
        Double averageScore,
        String mode,
        boolean followUpChoiceRequired,
        boolean deepTrainingActive,
        int deepTrainingAsked,
        int deepTrainingPassStreak,
        InterviewState returnState
) {

    static InterviewStatusResponse from(InterviewContext context, InterviewProperties properties) {
        boolean training = context.isTrainingMode();
        boolean deepTrainingActive = context.getState() == InterviewState.DEEP_TRAINING;
        return new InterviewStatusResponse(
                context.getSessionId(),
                context.getState(),
                context.getState().label(),
                context.totalQuestionsAsked(),
                properties.plannedTotal(),
                remainingQuestions(context, properties),
                context.getCurrentQuestion(),
                context.getCurrentFollowUpCount(),
                properties.getMaxFollowUps(),
                context.isCurrentQuestionFollowUp(),
                context.getCurrentDifficulty() == null ? null : context.getCurrentDifficulty().label(),
                training ? context.lastScore() : null,
                training ? context.averageScore() : null,
                context.getMode(),
                training && context.getPendingFollowUpQuestion() != null,
                deepTrainingActive,
                context.getDeepTrainingAsked(),
                context.getDeepTrainingConsecutivePass(),
                context.getDeepTrainingReturnState());
    }

    private static int remainingQuestions(InterviewContext context, InterviewProperties properties) {
        InterviewState state = context.getState();
        if (state == InterviewState.DEEP_TRAINING) {
            // 深度训练中按进入前的主面试阶段计算剩余题数
            state = context.getDeepTrainingReturnState() == null ? InterviewState.FINISHED : context.getDeepTrainingReturnState();
        }
        if (!state.questioning() && state != InterviewState.OPENING) {
            return 0;
        }
        int remaining = 0;
        for (InterviewState phase : new InterviewState[]{InterviewState.BASICS, InterviewState.PROJECT, InterviewState.DEEP}) {
            if (state != InterviewState.OPENING && phase.ordinal() < state.ordinal()) {
                continue;
            }
            remaining += Math.max(0, properties.maxQuestionsFor(phase) - context.questionsInPhase(phase));
        }
        return remaining;
    }
}
