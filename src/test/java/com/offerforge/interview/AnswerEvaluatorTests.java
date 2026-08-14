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
 * 回答质量评估单元测试：透传模型结果、模型异常兜底、好坏分档阈值。
 */
class AnswerEvaluatorTests {

    @Test
    void evaluateReturnsStructuredResultFromClient() {
        AnswerEvaluator evaluator = new AnswerEvaluator(new MockAiModelClient());

        AnswerEvaluation poor = evaluator.evaluate("q", "ref", "嗯。");
        assertThat(poor.overall()).isEqualTo(3.0);
        assertThat(poor.missedPoints()).isNotEmpty();

        AnswerEvaluation strong = evaluator.evaluate("q", "ref",
                "这是一个足够长的回答，覆盖了主要知识点，并且展开了具体细节说明，长度超过三十个字符。");
        assertThat(strong.overall()).isEqualTo(8.0);
        assertThat(strong.feedback()).isNotBlank();
    }

    @Test
    void evaluateFallsBackToMidScoreWhenClientReturnsNull() {
        StubClient client = new StubClient();
        client.detail = null;
        AnswerEvaluator evaluator = new AnswerEvaluator(client);

        AnswerEvaluation evaluation = evaluator.evaluate("q", null, "任意回答");
        assertThat(evaluation.overall()).isEqualTo(5.0);
        assertThat(evaluation.missedPoints()).isEmpty();
        assertThat(evaluation.wrongPoints()).isEmpty();
    }

    @Test
    void poorAndStrongThresholdBoundaries() {
        AnswerEvaluator evaluator = new AnswerEvaluator(new MockAiModelClient());

        // <4 触发追问，>=7 推进/提难度，4-6 保持
        assertThat(evaluator.isPoor(3.9)).isTrue();
        assertThat(evaluator.isPoor(4.0)).isFalse();
        assertThat(evaluator.isStrong(6.9)).isFalse();
        assertThat(evaluator.isStrong(7.0)).isTrue();
    }

    /** 可控返回值的桩客户端 */
    private static class StubClient implements AiModelClient {

        private AnswerEvaluation detail;

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
            return detail;
        }

        @Override
        public String generateFollowUpQuestion(String prompt) {
            return "";
        }
    }
}
