package com.offerforge.training;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!redis")
public class InMemoryTrainingSessionStore implements TrainingSessionStore {

    private final Map<String, TrainingContext> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(TrainingContext context) {
        sessions.put(context.getSessionId(), context);
    }

    @Override
    public Optional<TrainingContext> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    @Override
    public boolean hasActiveSession(Long userId) {
        return sessions.values().stream()
                .anyMatch(context -> context.getUserId() == userId && !context.isFinished());
    }
}
