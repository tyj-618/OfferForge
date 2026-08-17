package com.offerforge.training;

import java.util.Optional;

/**
 * 专项训练会话存储：与面试会话一致采用内存/Redis 双实现（长 TTL 支持刷新恢复）。
 */
public interface TrainingSessionStore {

    void save(TrainingContext context);

    Optional<TrainingContext> find(String sessionId);

    void remove(String sessionId);

    /** 该用户是否存在未完成的专项训练会话（每用户同时只能进行 1 场） */
    boolean hasActiveSession(Long userId);
}
