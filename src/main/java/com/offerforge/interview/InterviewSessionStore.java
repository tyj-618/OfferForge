package com.offerforge.interview;

import java.util.Optional;

/**
 * 面试会话上下文存储，与 TokenStore 一致采用内存/Redis 双实现。
 */
public interface InterviewSessionStore {

    void save(InterviewContext context);

    Optional<InterviewContext> find(String sessionId);

    void remove(String sessionId);

    /** 该用户是否存在未结束的面试会话（限流用：每用户同时只能有 1 场进行中的面试） */
    boolean hasActiveSession(Long userId);

    /** 取该用户未结束的面试会话（任务 4：暂存续考；无则 empty，多场时取最近创建的一场） */
    Optional<InterviewContext> findActiveSession(Long userId);
}
