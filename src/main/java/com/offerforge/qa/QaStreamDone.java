package com.offerforge.qa;

import java.util.List;

/**
 * 快捷提问流式 done 事件载荷：本次回答引用的知识条目 id。
 */
public record QaStreamDone(List<Long> referencedKnowledgeIds) {
}
