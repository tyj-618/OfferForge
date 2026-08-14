package com.offerforge.interview;

import java.util.Optional;

/**
 * 面试会话上下文存储，与 TokenStore 一致采用内存/Redis 双实现。
 */
public interface InterviewSessionStore {

    void save(InterviewContext context);

    Optional<InterviewContext> find(String sessionId);

    void remove(String sessionId);
}
