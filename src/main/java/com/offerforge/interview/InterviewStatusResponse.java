package com.offerforge.interview;

/**
 * 面试进度视图，供前端展示当前阶段、已问/剩余题数、追问进度与当前难度。
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
        double averageScore
) {

    static InterviewStatusResponse from(InterviewContext context, InterviewProperties properties) {
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
                context.lastScore(),
                context.averageScore());
    }

    private static int remainingQuestions(InterviewContext context, InterviewProperties properties) {
        InterviewState state = context.getState();
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
