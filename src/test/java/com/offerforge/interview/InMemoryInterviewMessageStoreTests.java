package com.offerforge.interview;

import com.offerforge.ai.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryInterviewMessageStoreTests {

    private final InterviewProperties properties = propertiesWithWindow(4);
    private final InMemoryInterviewMessageStore store = new InMemoryInterviewMessageStore(properties);

    @Test
    void appendKeepsSlidingWindowOfRecentMessages() {
        store.append("s1", List.of(
                ChatMessage.user("m1"), ChatMessage.assistant("m2"), ChatMessage.user("m3")));
        store.append("s1", List.of(ChatMessage.assistant("m4"), ChatMessage.user("m5"), ChatMessage.assistant("m6")));

        List<ChatMessage> history = store.list("s1");
        assertThat(history).hasSize(4);
        assertThat(history.get(0).content()).isEqualTo("m3");
        assertThat(history.get(3).content()).isEqualTo("m6");
    }

    @Test
    void sessionsAreIsolatedAndClearRemovesHistory() {
        store.append("s1", List.of(ChatMessage.user("a")));
        store.append("s2", List.of(ChatMessage.user("b")));

        assertThat(store.list("s1")).extracting(ChatMessage::content).containsExactly("a");
        assertThat(store.list("s2")).extracting(ChatMessage::content).containsExactly("b");

        store.clear("s1");
        assertThat(store.list("s1")).isEmpty();
        assertThat(store.list("s2")).hasSize(1);
    }

    @Test
    void unknownSessionReturnsEmptyList() {
        assertThat(store.list("missing")).isEmpty();
    }

    private InterviewProperties propertiesWithWindow(int window) {
        InterviewProperties interviewProperties = new InterviewProperties();
        interviewProperties.setMessageWindow(window);
        return interviewProperties;
    }
}
