package com.offerforge.interview;

import java.util.List;

/**
 * 面试进度视图，供前端展示当前阶段、已问/剩余题数、追问进度、当前难度与深度训练状态。
 * 实战模式过程免评分：lastScore/averageScore 返回 null（评分仍入库供结束报告）。
 * openingMessage/history/evaluating：刷新恢复凭此完整重建对话（history 含用户作答与点评，不含参考答案）。
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
        /** 已废弃：旧版低分选择卡标记，新流程恒为 false，保留仅为响应体兼容 */
        boolean followUpChoiceRequired,
        boolean deepTrainingActive,
        int deepTrainingAsked,
        int deepTrainingPassStreak,
        InterviewState returnState,
        /** 最近一道已作答题目所属资料分组（任务 4：「深入该模块」跳转专项训练用；无作答记录时为 null） */
        String lastAnswerCategory,
        /** 开场话术（自我介绍引导）：刷新恢复开场阶段用；旧会话为 null */
        String openingMessage,
        /** 回合评估中：前端恢复时轮询等待未完成回合 */
        boolean evaluating,
        /** 已作答回合完整记录：前端重建历史对话（含题面/回答/评分/导师点评） */
        List<QuestionRecord> history
) {

    static InterviewStatusResponse from(InterviewContext context, InterviewProperties properties) {
        boolean training = context.isTrainingMode();
        boolean deepTrainingActive = context.getState() == InterviewState.DEEP_TRAINING;
        var history = context.getQuestionHistory();
        String lastAnswerCategory = history.isEmpty()
                ? null
                : history.get(history.size() - 1).getKnowledgePoint();
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
                context.getDeepTrainingReturnState(),
                lastAnswerCategory,
                context.getOpeningMessage(),
                context.isEvaluating(),
                List.copyOf(context.getQuestionHistory()));
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
