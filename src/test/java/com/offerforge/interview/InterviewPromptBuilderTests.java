package com.offerforge.interview;

import com.offerforge.ai.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 面试 Prompt 构建单元测试：实战模式真人面试官人设（作答概况/简历注入）、
 * 训练模式极简衔接、追问话术承接候选人作答内容。
 */
class InterviewPromptBuilderTests {

    private final InterviewPromptBuilder builder = new InterviewPromptBuilder();

    private static String lastUserContent(List<ChatMessage> messages) {
        return messages.get(messages.size() - 1).content();
    }

    @Test
    void practiceModeInjectsLastAnswerAndResumeWithPersonaRequirements() {
        List<ChatMessage> messages = builder.buildInterviewerMessages(
                List.of(), InterviewState.BASICS, "HashMap 的原理？", InterviewContext.MODE_PRACTICE,
                "上一轮回答出色。覆盖到的要点：线程状态", "候选人：张三。技能：Java、Spring。项目经历：商城系统。");

        assertThat(messages.get(0).content()).contains("AI 面试官");
        String instruction = lastUserContent(messages);
        assertThat(instruction).contains("<task>interviewer</task>");
        assertThat(instruction).contains("<last-answer>上一轮回答出色。覆盖到的要点：线程状态</last-answer>");
        assertThat(instruction).contains("<resume>候选人：张三。技能：Java、Spring。项目经历：商城系统。</resume>");
        // 真人面试官人设：适度肯定但不透露评分，借简历桥接转场
        assertThat(instruction).contains("适度、具体地肯定").contains("绝不透露分数");
        assertThat(instruction).contains("简历信息作桥接");
        assertThat(instruction).contains("<question>HashMap 的原理？</question>");
    }

    @Test
    void practiceModeWithoutResumeUsesNeutralTransition() {
        List<ChatMessage> messages = builder.buildInterviewerMessages(
                List.of(), InterviewState.BASICS, "HashMap 的原理？", InterviewContext.MODE_PRACTICE,
                "上一轮回答基本合格。", null);

        String instruction = lastUserContent(messages);
        assertThat(instruction).contains("<last-answer>").doesNotContain("<resume>");
        assertThat(instruction).contains("自然的过渡语衔接").doesNotContain("简历信息作桥接");
    }

    @Test
    void trainingModeKeepsMinimalTransitionWithoutPersonaContext() {
        List<ChatMessage> messages = builder.buildInterviewerMessages(
                List.of(), InterviewState.PROJECT, "项目里如何做缓存？", InterviewContext.MODE_TRAINING,
                "上一轮回答出色。", "候选人：张三。");

        String instruction = lastUserContent(messages);
        assertThat(instruction).contains("<task>interviewer</task>");
        assertThat(instruction).contains("极简的衔接语").contains("不要点评或总结");
        // 导师反馈已由独立气泡给出，出题指令不再注入作答概况与简历
        assertThat(instruction).doesNotContain("<last-answer>").doesNotContain("<resume>");
        assertThat(instruction).contains("<question>项目里如何做缓存？</question>");
    }

    @Test
    void followUpCarriesCandidateAnswerAndFindings() {
        List<ChatMessage> messages = builder.buildFollowUpMessages(
                List.of(), "HashMap 的原理？", "只知道是数组存的",
                List.of("hash 冲突解决"), List.of("链表不会转红黑树"));

        String instruction = lastUserContent(messages);
        assertThat(instruction).contains("<task>followup</task>");
        assertThat(instruction).contains("<candidate-answer>只知道是数组存的</candidate-answer>");
        // 追问要求承接候选人具体作答，评估发现仅供组织方向
        assertThat(instruction).contains("承接候选人的实际作答内容");
        assertThat(instruction).contains("hash 冲突解决").contains("链表不会转红黑树");
        assertThat(instruction).contains("不要原样照念");
        assertThat(instruction).contains("<question>HashMap 的原理？</question>");
    }

    @Test
    void followUpWithoutFindingsOmitsFindingsBlock() {
        List<ChatMessage> messages = builder.buildFollowUpMessages(
                List.of(), "HashMap 的原理？", null, List.of(), List.of());

        String instruction = lastUserContent(messages);
        assertThat(instruction).doesNotContain("<candidate-answer>").doesNotContain("评估发现的薄弱点");
        assertThat(instruction).contains("<task>followup</task>");
    }

    @Test
    void historyIsPreservedBetweenSystemAndInstruction() {
        List<ChatMessage> history = List.of(ChatMessage.assistant("上一题"), ChatMessage.user("我的回答"));

        List<ChatMessage> messages = builder.buildInterviewerMessages(
                history, InterviewState.BASICS, "新题", InterviewContext.MODE_PRACTICE, null, null);

        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).role()).isEqualTo(ChatMessage.Role.SYSTEM);
        assertThat(messages.get(1).content()).isEqualTo("上一题");
        assertThat(messages.get(2).content()).isEqualTo("我的回答");
        assertThat(messages.get(3).role()).isEqualTo(ChatMessage.Role.USER);
    }
}
