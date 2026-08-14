package com.offerforge.interview;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!redis")
public class InMemoryInterviewSessionStore implements InterviewSessionStore {

    private final Map<String, InterviewContext> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(InterviewContext context) {
        sessions.put(context.getSessionId(), context);
    }

    @Override
    public Optional<InterviewContext> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }
}
