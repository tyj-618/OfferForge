package com.offerforge.interview;

import com.offerforge.ai.AiStreamChunkConsumer;

import java.io.IOException;

/**
 * 回合流式输出槽：chunk 为对话内容帧，segment 标记新气泡开始（前端据此新建对话消息），
 * progress 为阶段状态提示帧（如"正在评估你的回答…"，不进入对话记录）。
 * <p>唯一抽象方法是 chunk，既有 AiStreamChunkConsumer lambda 可直接作为本接口实例传入。</p>
 */
public interface InterviewStreamSink {

    void chunk(String text) throws IOException;

    /** 新气泡开始：训练模式导师反馈与下一题分属两个对话气泡 */
    default void segment() throws IOException {
    }

    /** 阶段状态提示：阻塞式 LLM 调用前下发，避免前端长时间只见静态动画 */
    default void progress(String text) throws IOException {
    }

    /** 兼容适配：仅透传 chunk，segment/progress 为无操作 */
    static InterviewStreamSink of(AiStreamChunkConsumer consumer) {
        return consumer::accept;
    }
}
