package com.offerforge.interview;

import com.offerforge.ai.AiEvaluation;
import com.offerforge.ai.AiModelClient;
import com.offerforge.ai.AiStreamChunkConsumer;
import com.offerforge.ai.AiTextResult;
import com.offerforge.ai.AnswerEvaluation;
import com.offerforge.ai.ChatMessage;
import com.offerforge.ai.MockAiModelClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 追问策略单元测试：触发条件、次数上限、Prompt 构建与生成兜底。
 */
class FollowUpStrategyTests {

    private final InterviewProperties properties = new InterviewProperties();
    private final FollowUpStrategy strategy = new FollowUpStrategy(properties, new MockAiModelClient());

    @Test
    void triggersWhenScorePoorAndUnderLimit() {
        assertThat(strategy.shouldFollowUp(3.9, 0)).isTrue();
        assertThat(strategy.shouldFollowUp(0.0, 1)).isTrue();
    }

    @Test
    void doesNotTriggerWhenScoreAcceptable() {
        assertThat(strategy.shouldFollowUp(4.0, 0)).isFalse();
        assertThat(strategy.shouldFollowUp(5.0, 1)).isFalse();
        assertThat(strategy.shouldFollowUp(8.0, 0)).isFalse();
    }

    @Test
    void thirdFollowUpIsBlockedByLimit() {
        // 每道题最多 2 次追问：已追问 2 次后第 3 次被阻止
        assertThat(strategy.shouldFollowUp(3.0, properties.getMaxFollowUps())).isFalse();
        assertThat(strategy.maxFollowUps()).isEqualTo(2);
    }

    @Test
    void promptCarriesOriginalQuestionAndEvaluationFindings() {
        String prompt = strategy.buildPrompt("HashMap 的原理？",
                List.of("hash 冲突解决"), List.of("链表不会转红黑树"));

        assertThat(prompt).contains("<task>followup-gen</task>");
        assertThat(prompt).contains("HashMap 的原理？");
        assertThat(prompt).contains("hash 冲突解决");
        assertThat(prompt).contains("链表不会转红黑树");
        assertThat(prompt).contains("换一个新角度").contains("难度比原题略低");
    }

    @Test
    void generatedFollowUpKeepsSameKnowledgePoint() {
        String followUp = strategy.generateFollowUpQuestion("HashMap 的原理？",
                List.of("hash 冲突解决"), List.of());

        assertThat(followUp).contains("HashMap 的原理？").contains("换个角度");
    }

    @Test
    void fallsBackToConcreteQuestionWhenModelReturnsBlank() {
        FollowUpStrategy fallback = new FollowUpStrategy(properties, new BlankClient());

        String followUp = fallback.generateFollowUpQuestion("线程池参数如何设置？", List.of(), List.of());
        assertThat(followUp).contains("线程池参数如何设置？").contains("实际场景");
    }

    /** 追问生成返回空串的桩客户端，验证服务端兜底题面 */
    private static class BlankClient implements AiModelClient {

        @Override
        public AiTextResult generateText(List<ChatMessage> messages) {
            return new AiTextResult("", "stub", 0, 0);
        }

        @Override
        public void generateStream(List<ChatMessage> messages, AiStreamChunkConsumer chunkConsumer) {
        }

        @Override
        public AiEvaluation evaluateAnswer(String question, String candidateAnswer, String userAnswer) {
            return new AiEvaluation(5, "stub");
        }

        @Override
        public AnswerEvaluation evaluateAnswerDetail(String question, String candidateAnswer, String userAnswer) {
            return new AnswerEvaluation(5, 5, 5, 5, List.of(), List.of(), "stub");
        }

        @Override
        public String generateFollowUpQuestion(String prompt) {
            return "  ";
        }
    }
}
