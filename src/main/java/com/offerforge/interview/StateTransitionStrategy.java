package com.offerforge.interview;

import org.springframework.stereotype.Component;

/**
 * 状态转移策略：根据回答质量决定下一题方向。
 * <ul>
 *   <li>评分 >= 7 → 推进到下一阶段（连续高分且难度可提升时留在当前阶段出更高难度题）</li>
 *   <li>评分 4-6 → 保持当前阶段，换一道题（达到阶段题量上限则推进）</li>
 *   <li>评分 < 4 → 追问（同知识点换角度），最多 maxFollowUps 次，之后换题或推进</li>
 * </ul>
 * 纯逻辑组件，不依赖存储，便于单元测试覆盖全部转移路径。
 */
@Component
public class StateTransitionStrategy {

    public enum Action {
        /** 追问当前题（同知识点换角度） */
        FOLLOW_UP,
        /** 保持当前阶段，换新题 */
        NEW_QUESTION,
        /** 推进到下一阶段 */
        ADVANCE,
        /** 结束面试 */
        FINISH
    }

    public record DecisionInput(
            InterviewState state,
            double score,
            int followUpsUsed,
            int questionsInPhase,
            boolean questionPoolExhausted,
            boolean canRaiseDifficulty
    ) {
        /** 便利构造器：不考虑难度提升（兼容既有调用与测试） */
        public DecisionInput(InterviewState state, double score, int followUpsUsed,
                             int questionsInPhase, boolean questionPoolExhausted) {
            this(state, score, followUpsUsed, questionsInPhase, questionPoolExhausted, false);
        }
    }

    private static final int ADVANCE_THRESHOLD = 7;
    private static final int FOLLOW_UP_THRESHOLD = 4;

    private final InterviewProperties properties;

    public StateTransitionStrategy(InterviewProperties properties) {
        this.properties = properties;
    }

    public Action decide(DecisionInput input) {
        InterviewState state = input.state();
        if (state == InterviewState.FINISHED || state == InterviewState.CLOSING) {
            return Action.FINISH;
        }
        if (state == InterviewState.OPENING) {
            // 开场后的首次作答（自我介绍）不评分，直接进入基础考察
            return Action.ADVANCE;
        }
        if (input.questionPoolExhausted()) {
            // 题库耗尽时强制推进，避免死循环
            return Action.ADVANCE;
        }
        boolean capReached = input.questionsInPhase() >= properties.maxQuestionsFor(state);
        if (input.score() >= ADVANCE_THRESHOLD) {
            // 连续高分且难度可提升：留在当前阶段出更高难度题；否则推进下一阶段
            return input.canRaiseDifficulty() && !capReached ? Action.NEW_QUESTION : Action.ADVANCE;
        }
        if (input.score() >= FOLLOW_UP_THRESHOLD) {
            return capReached ? Action.ADVANCE : Action.NEW_QUESTION;
        }
        // 低分：优先追问，追问次数用尽后换题或推进
        if (input.followUpsUsed() < properties.getMaxFollowUps()) {
            return Action.FOLLOW_UP;
        }
        return capReached ? Action.ADVANCE : Action.NEW_QUESTION;
    }
}
