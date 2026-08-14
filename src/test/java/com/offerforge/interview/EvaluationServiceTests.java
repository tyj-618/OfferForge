package com.offerforge.interview;

import com.offerforge.ai.AiEvaluation;
import com.offerforge.ai.AiGeneratedQuestion;
import com.offerforge.ai.AiModelClient;
import com.offerforge.ai.AiStreamChunkConsumer;
import com.offerforge.ai.AiTextResult;
import com.offerforge.ai.AnswerEvaluation;
import com.offerforge.ai.ChatMessage;
import com.offerforge.ai.MockAiModelClient;
import com.offerforge.ai.ReportSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单题评估服务单元测试：四维度评分透传、模型异常兜底、好坏分档阈值。
 */
class EvaluationServiceTests {

    private static final String LONG_ANSWER = "这是一个足够长的回答，覆盖了主要知识点，并且展开了具体细节说明，长度超过三十个字符。";

    @Test
    void evaluatePassesThroughDimensionScoresByAnswerQuality() {
        EvaluationService service = new EvaluationService(new MockAiModelClient());

        AnswerEvaluation poor = service.evaluate("q", "HashMap 原理", "参考答案", "嗯。");
        assertThat(poor.overall()).isEqualTo(3.0);
        assertThat(poor.accuracy()).isEqualTo(3.0);
        assertThat(poor.depth()).isEqualTo(3.0);
        assertThat(poor.missedPoints()).isNotEmpty();

        AnswerEvaluation strong = service.evaluate("q", "HashMap 原理", "参考答案", LONG_ANSWER);
        assertThat(strong.overall()).isEqualTo(8.0);
        assertThat(strong.accuracy()).isEqualTo(8.0);
        assertThat(strong.completeness()).isEqualTo(8.0);
        assertThat(strong.clarity()).isEqualTo(8.0);
        assertThat(strong.depth()).isEqualTo(8.0);
        assertThat(strong.keyPoints()).isNotEmpty();
        assertThat(strong.feedback()).isNotBlank();
    }

    @Test
    void evaluateAllowsNullKnowledgePointAndReferenceAnswer() {
        // 项目类问题无知识点与标准答案，评估不应报错
        EvaluationService service = new EvaluationService(new MockAiModelClient());

        AnswerEvaluation evaluation = service.evaluate("介绍一下你的项目", null, null, LONG_ANSWER);
        assertThat(evaluation.overall()).isEqualTo(8.0);
    }

    @Test
    void evaluateFallsBackToMidScoreWhenClientReturnsNull() {
        EvaluationService service = new EvaluationService(new NullDetailClient());

        AnswerEvaluation evaluation = service.evaluate("q", null, null, "任意回答");
        assertThat(evaluation.overall()).isEqualTo(5.0);
        assertThat(evaluation.accuracy()).isEqualTo(5.0);
        assertThat(evaluation.missedPoints()).isEmpty();
        assertThat(evaluation.wrongPoints()).isEmpty();
    }

    @Test
    void poorAndStrongThresholdBoundaries() {
        EvaluationService service = new EvaluationService(new MockAiModelClient());

        // <4 触发追问，>=7 推进/提难度，4-6 保持
        assertThat(service.isPoor(3.9)).isTrue();
        assertThat(service.isPoor(4.0)).isFalse();
        assertThat(service.isStrong(6.9)).isFalse();
        assertThat(service.isStrong(7.0)).isTrue();
    }

    /** 结构化评估返回 null 的桩客户端，验证服务端兜底 */
    private static class NullDetailClient implements AiModelClient {

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
        public AnswerEvaluation evaluateAnswerDetail(String question, String knowledgePoint,
                                                     String candidateAnswer, String userAnswer) {
            return null;
        }

        @Override
        public String generateFollowUpQuestion(String prompt) {
            return "";
        }

        @Override
        public ReportSummary generateReportSummary(String prompt) {
            return null;
        }

        @Override
        public String parseResume(String rawText) {
            return null;
        }

        @Override
        public List<AiGeneratedQuestion> generateProjectQuestions(String prompt) {
            return List.of();
        }

        @Override
        public AiGeneratedQuestion generateDeepQuestion(String prompt) {
            return null;
        }
    }
}
