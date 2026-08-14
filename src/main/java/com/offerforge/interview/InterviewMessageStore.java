package com.offerforge.interview;

import com.offerforge.ai.ChatMessage;

import java.util.List;

/**
 * 面试对话历史存储（key: interview:{id}:messages）。
 * 实现方负责滑动窗口压缩，list 返回窗口内的完整 messages。
 */
public interface InterviewMessageStore {

    void append(String sessionId, List<ChatMessage> messages);

    List<ChatMessage> list(String sessionId);

    void clear(String sessionId);
}
