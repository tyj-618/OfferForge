package com.offerforge.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mock 客户端的面试能力：确定性评分分档 + 面试任务流式输出。
 */
class MockAiModelClientInterviewTests {

    private final MockAiModelClient client = new MockAiModelClient();

    @Test
    void evaluateScoresDeterministicallyByAnswerLength() {
        assertThat(client.evaluateAnswer("q", null, null).score()).isZero();
        assertThat(client.evaluateAnswer("q", null, "   ").score()).isZero();
        assertThat(client.evaluateAnswer("q", null, "嗯").score()).isEqualTo(3);
        assertThat(client.evaluateAnswer("q", null, "这个回答长度在十到二十九个字符之间哦").score()).isEqualTo(5);
        assertThat(client.evaluateAnswer("q", null,
                "这是一个足够长的回答，覆盖了主要知识点，并且展开了具体细节说明，长度超过三十个字符。").score()).isEqualTo(8);
    }

    @Test
    void evaluateDetailScoresByAnswerLengthWithFindings() {
        AnswerEvaluation blank = client.evaluateAnswerDetail("q", null, "  ");
        assertThat(blank.overall()).isZero();
        assertThat(blank.missedPoints()).isNotEmpty();

        AnswerEvaluation poor = client.evaluateAnswerDetail("q", null, "嗯。");
        assertThat(poor.overall()).isEqualTo(3.0);
        assertThat(poor.missedPoints()).isNotEmpty();

        AnswerEvaluation mid = client.evaluateAnswerDetail("q", null, "这个回答长度在十到二十九个字符之间哦");
        assertThat(mid.overall()).isEqualTo(5.0);

        AnswerEvaluation strong = client.evaluateAnswerDetail("q", null,
                "这是一个足够长的回答，覆盖了主要知识点，并且展开了具体细节说明，长度超过三十个字符。");
        assertThat(strong.overall()).isEqualTo(8.0);
        assertThat(strong.missedPoints()).isEmpty();
        assertThat(strong.wrongPoints()).isEmpty();
    }

    @Test
    void followUpGenerationKeepsOriginalQuestion() {
        String prompt = "<task>followup-gen</task>\n候选人对问题「HashMap 的原理？」的回答不够理想。\n遗漏的要点：冲突解决";

        assertThat(client.generateFollowUpQuestion(prompt))
                .contains("HashMap 的原理？").contains("换个角度");
        // 非流式生成入口同样可用（FollowUpStrategy 走 generateText 路径时不受流式分块影响）
        assertThat(client.generateText(List.of(ChatMessage.user(prompt))).content())
                .contains("HashMap 的原理？");
    }

    @Test
    void streamInterviewerTaskEmitsQuestionInChunks() throws IOException {
        String collected = stream(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("我熟悉 Java"),
                ChatMessage.user("<task>interviewer</task><phase>基础考察</phase><question>HashMap 的原理？</question>")));

        assertThat(collected).contains("模拟面试官").contains("HashMap 的原理？");
    }

    @Test
    void streamFollowUpTaskKeepsSameKnowledgePoint() throws IOException {
        String collected = stream(List.of(
                ChatMessage.user("<task>followup</task><question>线程池参数如何设置？</question>")));

        assertThat(collected).contains("模拟追问").contains("线程池参数如何设置？");
    }

    @Test
    void streamWithoutTaskMarkerFallsBackToQaBehavior() throws IOException {
        String collected = stream(List.of(
                ChatMessage.user("<question>任意问题</question>\n<knowledge>[ref: 1] 题面</knowledge>")));

        assertThat(collected).contains("模拟回答").contains("id: 1");
    }

    private String stream(List<ChatMessage> messages) throws IOException {
        StringBuilder collected = new StringBuilder();
        client.generateStream(messages, collected::append);
        return collected.toString();
    }
}
