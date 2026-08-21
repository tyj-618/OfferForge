package com.offerforge.interview;

import com.offerforge.ai.AssistantStyle;
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
                "上一轮回答出色。覆盖到的要点：线程状态", "候选人：张三。技能：Java、Spring。项目经历：商城系统。",
                AssistantStyle.FRIENDLY, null, List.of());

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
                "上一轮回答基本合格。", null, AssistantStyle.FRIENDLY, null, List.of());

        String instruction = lastUserContent(messages);
        assertThat(instruction).contains("<last-answer>").doesNotContain("<resume>");
        assertThat(instruction).contains("自然的过渡语衔接").doesNotContain("简历信息作桥接");
    }

    @Test
    void trainingModeKeepsMinimalTransitionWithoutPersonaContext() {
        List<ChatMessage> messages = builder.buildInterviewerMessages(
                List.of(), InterviewState.PROJECT, "项目里如何做缓存？", InterviewContext.MODE_TRAINING,
                "上一轮回答出色。", "候选人：张三。", AssistantStyle.FRIENDLY, null, List.of());

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
                List.of("hash 冲突解决"), List.of("链表不会转红黑树"), AssistantStyle.FRIENDLY);

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
                List.of(), "HashMap 的原理？", null, List.of(), List.of(), AssistantStyle.FRIENDLY);

        String instruction = lastUserContent(messages);
        assertThat(instruction).doesNotContain("<candidate-answer>").doesNotContain("评估发现的薄弱点");
        assertThat(instruction).contains("<task>followup</task>");
    }

    @Test
    void historyIsPreservedBetweenSystemAndInstruction() {
        List<ChatMessage> history = List.of(ChatMessage.assistant("上一题"), ChatMessage.user("我的回答"));

        List<ChatMessage> messages = builder.buildInterviewerMessages(
                history, InterviewState.BASICS, "新题", InterviewContext.MODE_PRACTICE, null, null,
                AssistantStyle.FRIENDLY, null, List.of());

        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).role()).isEqualTo(ChatMessage.Role.SYSTEM);
        assertThat(messages.get(1).content()).isEqualTo("上一题");
        assertThat(messages.get(2).content()).isEqualTo("我的回答");
        assertThat(messages.get(3).role()).isEqualTo(ChatMessage.Role.USER);
    }

    @Test
    void strictStyleInjectsEfficiencyNoteAndRemovesSmallTalk() {
        List<ChatMessage> messages = builder.buildInterviewerMessages(
                List.of(), InterviewState.BASICS, "HashMap 的原理？", InterviewContext.MODE_PRACTICE,
                "上一轮回答出色。", null, AssistantStyle.STRICT, null, List.of());

        // 系统人设追加严肃专业风格指令
        assertThat(messages.get(0).content()).contains("严肃专业").contains("铁面无私");
        String instruction = lastUserContent(messages);
        // 严肃风格不要求肯定/寒暄，直接衔接出题
        assertThat(instruction).contains("不表扬、不安抚、不寒暄").doesNotContain("适度、具体地肯定");
    }

    @Test
    void friendlyStyleInjectsInformationDensityNote() {
        List<ChatMessage> messages = builder.buildInterviewerMessages(
                List.of(), InterviewState.BASICS, "HashMap 的原理？", InterviewContext.MODE_PRACTICE,
                null, null, AssistantStyle.FRIENDLY, null, List.of());

        assertThat(messages.get(0).content()).contains("和蔼可亲").contains("信息浓度");
    }

    @Test
    void nullStyleFallsBackToFriendly() {
        List<ChatMessage> messages = builder.buildInterviewerMessages(
                List.of(), InterviewState.BASICS, "新题", InterviewContext.MODE_PRACTICE, null, null, null,
                null, List.of());

        assertThat(messages.get(0).content()).contains("和蔼可亲");
    }

    @Test
    void setupBlockInjectsPositionAndFocusCategories() {
        List<ChatMessage> messages = builder.buildInterviewerMessages(
                List.of(), InterviewState.BASICS, "HashMap 的原理？", InterviewContext.MODE_PRACTICE,
                null, null, AssistantStyle.FRIENDLY, "Java 后端开发", List.of("Java 基础", "我的笔记"));

        String instruction = lastUserContent(messages);
        assertThat(instruction).contains("<setup>");
        assertThat(instruction).contains("求职岗位：Java 后端开发");
        assertThat(instruction).contains("本场面试重点考察的资料标签：Java 基础、我的笔记");
        assertThat(instruction).contains("不要原样照念");
    }

    @Test
    void setupBlockOmittedWithoutPositionAndCategories() {
        List<ChatMessage> messages = builder.buildInterviewerMessages(
                List.of(), InterviewState.BASICS, "HashMap 的原理？", InterviewContext.MODE_PRACTICE,
                null, null, AssistantStyle.FRIENDLY, null, List.of());

        assertThat(lastUserContent(messages)).doesNotContain("<setup>");
    }

    @Test
    void introCheckPromptCarriesDialogueHistoryForCoreference() {
        // 借鉴 UniNook AI 助手：每次调用必注入对话历史，供模型消解省略主语/指代的表述
        List<ChatMessage> history = List.of(
                ChatMessage.assistant("请先做一个简短的自我介绍。"),
                ChatMessage.user("我是张三，有一个线上全栈项目 UniNook。"),
                ChatMessage.assistant("能具体说说 UniNook 的 Java 后端部分吗？"));

        String prompt = builder.buildIntroCheckPrompt("项目完全由我一人实现，技术栈有 spring boot、redis。",
                null, "Java 后端工程师", history);

        assertThat(prompt).contains("<dialogue-history>");
        assertThat(prompt).contains("面试官：能具体说说 UniNook 的 Java 后端部分吗？");
        assertThat(prompt).contains("候选人：我是张三，有一个线上全栈项目 UniNook。");
        // 指代消解与防重复索要指令
        assertThat(prompt).contains("结合对话历史理解其指向").contains("不得重复索要");
        // 兼容 Mock 的确定性标记格式保持不变
        assertThat(prompt).contains("自我介绍：项目完全由我一人实现");
    }

    @Test
    void introCheckPromptWithoutHistoryOmitsDialogueBlock() {
        String prompt = builder.buildIntroCheckPrompt("信息不全", null, null, List.of());

        assertThat(prompt).doesNotContain("<dialogue-history>").contains("NONE");
    }
}
