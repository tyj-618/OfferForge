package com.offerforge.interview;

import com.offerforge.ai.ChatMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!redis")
public class InMemoryInterviewMessageStore implements InterviewMessageStore {

    private final InterviewProperties properties;
    private final Map<String, List<ChatMessage>> histories = new ConcurrentHashMap<>();

    public InMemoryInterviewMessageStore(InterviewProperties properties) {
        this.properties = properties;
    }

    @Override
    public void append(String sessionId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        histories.compute(sessionId, (ignored, existing) -> {
            List<ChatMessage> merged = existing == null ? new ArrayList<>() : existing;
            merged.addAll(messages);
            return trimToWindow(merged);
        });
    }

    @Override
    public List<ChatMessage> list(String sessionId) {
        List<ChatMessage> history = histories.get(sessionId);
        return history == null ? List.of() : List.copyOf(history);
    }

    @Override
    public void clear(String sessionId) {
        histories.remove(sessionId);
    }

    private List<ChatMessage> trimToWindow(List<ChatMessage> messages) {
        int window = properties.getMessageWindow();
        if (messages.size() <= window) {
            return messages;
        }
        return new ArrayList<>(messages.subList(messages.size() - window, messages.size()));
    }
}
