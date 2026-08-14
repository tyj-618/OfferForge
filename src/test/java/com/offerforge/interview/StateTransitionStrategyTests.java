package com.offerforge.interview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态转移策略全路径单元测试：
 * >=7 推进 / 4-6 换题（达上限推进）/ <4 追问（最多2次，用尽后换题或推进）/ 边界与兜底。
 */
class StateTransitionStrategyTests {

    private final InterviewProperties properties = new InterviewProperties();
    private final StateTransitionStrategy strategy = new StateTransitionStrategy(properties);

    @Test
    void openingAlwaysAdvancesWithoutScoring() {
        assertThat(decide(InterviewState.OPENING, 0, 0, 0, false))
                .isEqualTo(StateTransitionStrategy.Action.ADVANCE);
    }

    @Test
    void closingAndFinishedAlwaysFinish() {
        assertThat(decide(InterviewState.CLOSING, 9, 0, 1, false))
                .isEqualTo(StateTransitionStrategy.Action.FINISH);
        assertThat(decide(InterviewState.FINISHED, 9, 0, 1, false))
                .isEqualTo(StateTransitionStrategy.Action.FINISH);
    }

    @Test
    void highScoreAdvancesToNextPhase() {
        assertThat(decide(InterviewState.BASICS, 8, 0, 1, false))
                .isEqualTo(StateTransitionStrategy.Action.ADVANCE);
    }

    @Test
    void boundaryScoreSevenStillAdvances() {
        assertThat(decide(InterviewState.DEEP, 7, 1, 2, false))
                .isEqualTo(StateTransitionStrategy.Action.ADVANCE);
    }

    @Test
    void midScoreKeepsPhaseWithNewQuestion() {
        assertThat(decide(InterviewState.BASICS, 5, 0, 1, false))
                .isEqualTo(StateTransitionStrategy.Action.NEW_QUESTION);
        assertThat(decide(InterviewState.PROJECT, 4, 0, 1, false))
                .isEqualTo(StateTransitionStrategy.Action.NEW_QUESTION);
    }

    @Test
    void midScoreAdvancesWhenPhaseCapReached() {
        assertThat(decide(InterviewState.BASICS, 5, 0, properties.getMaxBasicsQuestions(), false))
                .isEqualTo(StateTransitionStrategy.Action.ADVANCE);
        assertThat(decide(InterviewState.PROJECT, 6, 0, properties.getMaxProjectQuestions(), false))
                .isEqualTo(StateTransitionStrategy.Action.ADVANCE);
    }

    @Test
    void lowScoreTriggersFollowUpWithinLimit() {
        assertThat(decide(InterviewState.BASICS, 3, 0, 1, false))
                .isEqualTo(StateTransitionStrategy.Action.FOLLOW_UP);
        assertThat(decide(InterviewState.DEEP, 0, properties.getMaxFollowUps() - 1, 1, false))
                .isEqualTo(StateTransitionStrategy.Action.FOLLOW_UP);
    }

    @Test
    void lowScoreWithExhaustedFollowUpsFallsBackToNewQuestion() {
        assertThat(decide(InterviewState.BASICS, 2, properties.getMaxFollowUps(), 1, false))
                .isEqualTo(StateTransitionStrategy.Action.NEW_QUESTION);
    }

    @Test
    void lowScoreWithExhaustedFollowUpsAdvancesWhenCapReached() {
        assertThat(decide(InterviewState.DEEP, 1, properties.getMaxFollowUps(),
                properties.getMaxDeepQuestions(), false))
                .isEqualTo(StateTransitionStrategy.Action.ADVANCE);
    }

    @Test
    void exhaustedQuestionPoolForcesAdvanceRegardlessOfScore() {
        assertThat(decide(InterviewState.PROJECT, 2, 0, 0, true))
                .isEqualTo(StateTransitionStrategy.Action.ADVANCE);
        assertThat(decide(InterviewState.BASICS, 9, 0, 1, true))
                .isEqualTo(StateTransitionStrategy.Action.ADVANCE);
    }

    @Test
    void highScoreRaisesDifficultyWhenStreakAllows() {
        // 连续高分且难度可提升：留在当前阶段出更高难度题
        assertThat(decide(InterviewState.DEEP, 8, 0, 1, false, true))
                .isEqualTo(StateTransitionStrategy.Action.NEW_QUESTION);
        assertThat(decide(InterviewState.BASICS, 7, 0, 2, false, true))
                .isEqualTo(StateTransitionStrategy.Action.NEW_QUESTION);
    }

    @Test
    void highScoreAdvancesWhenRaiseAllowedButPhaseCapReached() {
        assertThat(decide(InterviewState.DEEP, 8, 0, properties.getMaxDeepQuestions(), false, true))
                .isEqualTo(StateTransitionStrategy.Action.ADVANCE);
    }

    @Test
    void highScoreAdvancesWhenDifficultyAlreadyHard() {
        // canRaise=false（已是 HARD 或连击不足）：仍推进下一阶段
        assertThat(decide(InterviewState.DEEP, 8, 0, 1, false, false))
                .isEqualTo(StateTransitionStrategy.Action.ADVANCE);
    }

    @Test
    void stateChainAdvancesInOrderAndTerminates() {
        assertThat(InterviewState.OPENING.next()).isEqualTo(InterviewState.BASICS);
        assertThat(InterviewState.BASICS.next()).isEqualTo(InterviewState.PROJECT);
        assertThat(InterviewState.PROJECT.next()).isEqualTo(InterviewState.DEEP);
        assertThat(InterviewState.DEEP.next()).isEqualTo(InterviewState.CLOSING);
        assertThat(InterviewState.CLOSING.next()).isEqualTo(InterviewState.FINISHED);
        assertThat(InterviewState.FINISHED.next()).isEqualTo(InterviewState.FINISHED);
        assertThat(InterviewState.FINISHED.terminal()).isTrue();
        assertThat(InterviewState.BASICS.questioning()).isTrue();
        assertThat(InterviewState.OPENING.questioning()).isFalse();
    }

    private StateTransitionStrategy.Action decide(InterviewState state, int score, int followUps,
                                                  int questionsInPhase, boolean poolExhausted) {
        return strategy.decide(new StateTransitionStrategy.DecisionInput(
                state, score, followUps, questionsInPhase, poolExhausted));
    }

    private StateTransitionStrategy.Action decide(InterviewState state, int score, int followUps,
                                                  int questionsInPhase, boolean poolExhausted, boolean canRaise) {
        return strategy.decide(new StateTransitionStrategy.DecisionInput(
                state, score, followUps, questionsInPhase, poolExhausted, canRaise));
    }
}
