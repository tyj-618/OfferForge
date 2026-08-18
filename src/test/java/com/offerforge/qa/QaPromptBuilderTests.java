package com.offerforge.qa;

import com.offerforge.ai.ChatMessage;
import com.offerforge.knowledge.RetrievedKnowledge;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QaPromptBuilderTests {

    private final QaPromptBuilder promptBuilder = new QaPromptBuilder();

    @Test
    void buildProducesSystemAndUserMessagesWithKnowledgeBlocks() {
        List<RetrievedKnowledge> knowledge = List.of(
                new RetrievedKnowledge(1L, "HashMap 的底层实现原理？", "数组+链表+红黑树", "Java集合", 1.0),
                new RetrievedKnowledge(2L, "ConcurrentHashMap 的区别？", "分段锁/CAS", "Java集合", 0.5)
        );

        List<ChatMessage> messages = promptBuilder.build("HashMap 原理", knowledge);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo(ChatMessage.Role.SYSTEM);
        assertThat(messages.get(0).content()).contains("严格基于").contains("未覆盖");
        assertThat(messages.get(1).role()).isEqualTo(ChatMessage.Role.USER);
        String userPrompt = messages.get(1).content();
        assertThat(userPrompt).contains("<question>\nHashMap 原理\n</question>");
        assertThat(userPrompt).contains("[ref: 1]").contains("[ref: 2]");
        assertThat(userPrompt).contains("数组+链表+红黑树");
        assertThat(userPrompt).endsWith("</knowledge>");
    }

    @Test
    void buildWithEmptyKnowledgeMarksNoReference() {
        List<ChatMessage> messages = promptBuilder.build("量子计算", List.of());

        assertThat(messages.get(1).content()).contains("（未检索到相关知识点）");
    }

    @Test
    void longAnswerIsTruncated() {
        String longAnswer = "长".repeat(1200);
        List<RetrievedKnowledge> knowledge = List.of(
                new RetrievedKnowledge(9L, "题目", longAnswer, "Java基础", 1.0)
        );

        List<ChatMessage> messages = promptBuilder.build("问题", knowledge);

        String userPrompt = messages.get(1).content();
        assertThat(userPrompt).doesNotContain(longAnswer);
        assertThat(userPrompt).contains("长".repeat(800) + "…");
    }

    @Test
    void historyIsInjectedBetweenSystemAndCurrentQuestion() {
        List<QaAskStreamRequest.HistoryEntry> history = List.of(
                new QaAskStreamRequest.HistoryEntry("user", "HashMap 原理"),
                new QaAskStreamRequest.HistoryEntry("assistant", "数组+链表+红黑树"),
                new QaAskStreamRequest.HistoryEntry("system", "应忽略的非法角色"));

        List<ChatMessage> messages = promptBuilder.build("那线程安全怎么解决", history, List.of());

        // system + user + assistant + 本轮 user，非法角色与空内容忽略
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).role()).isEqualTo(ChatMessage.Role.SYSTEM);
        assertThat(messages.get(1).content()).isEqualTo("HashMap 原理");
        assertThat(messages.get(2).role()).isEqualTo(ChatMessage.Role.ASSISTANT);
        assertThat(messages.get(3).role()).isEqualTo(ChatMessage.Role.USER);
        assertThat(messages.get(3).content()).contains("那线程安全怎么解决");
    }

    @Test
    void historyKeepsOnlyRecentMessages() {
        List<QaAskStreamRequest.HistoryEntry> history = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(new QaAskStreamRequest.HistoryEntry(i % 2 == 0 ? "user" : "assistant", "消息" + i));
        }

        List<ChatMessage> messages = promptBuilder.build("新问题", history, List.of());

        // system + 最近 6 条历史 + 本轮 user
        assertThat(messages).hasSize(8);
        assertThat(messages.get(1).content()).isEqualTo("消息14");
        assertThat(messages.get(6).content()).isEqualTo("消息19");
    }
}
