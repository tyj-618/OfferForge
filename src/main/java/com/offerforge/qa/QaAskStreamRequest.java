package com.offerforge.qa;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 快捷提问流式请求：question + 可选对话历史（前端会话内临时保存，用于追问的上下文理解）。
 */
public record QaAskStreamRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 500, message = "长度不能超过 500")
        String question,
        @Valid
        @Size(max = 20, message = "对话历史过长")
        List<HistoryEntry> history
) {

    /** 历史消息条目：role 仅接受 user/assistant，其余忽略 */
    public record HistoryEntry(
            @NotBlank
            String role,
            @Size(max = 8000)
            String content
    ) {
    }
}
