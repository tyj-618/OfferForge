package com.offerforge.qa;

import com.offerforge.auth.CurrentUserService;
import com.offerforge.common.ApiResponse;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/api/qa")
public class QaController {

    /** 与面试/训练流式同预算：检索 + 流式生成最坏覆盖 */
    private static final long STREAM_TIMEOUT_MILLIS = 180_000L;

    private static final Logger log = LoggerFactory.getLogger(QaController.class);

    private final QaService qaService;
    private final CurrentUserService currentUserService;
    private final Executor interviewStreamExecutor;

    public QaController(QaService qaService,
                        CurrentUserService currentUserService,
                        @Qualifier("interviewStreamExecutor") Executor interviewStreamExecutor) {
        this.qaService = qaService;
        this.currentUserService = currentUserService;
        this.interviewStreamExecutor = interviewStreamExecutor;
    }

    @PostMapping("/ask")
    public ApiResponse<QaResponse> ask(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody QaRequest request) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(qaService.ask(userId, request.question()));
    }

    /**
     * SSE 流式提问：message 事件为回答分块，done 事件携带引用知识条目 id，
     * error 事件携带 code/message（与面试/训练 SSE 契约一致，前端 sseRequest 统一处理续期/超时）。
     */
    @PostMapping(value = "/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody QaAskStreamRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        // 超时/异常时及时结束 emitter，避免异步任务对已失效连接的无效写入
        emitter.onTimeout(emitter::complete);
        emitter.onError(throwable -> emitter.complete());
        interviewStreamExecutor.execute(() -> writeStream(emitter, authorization, request));
        return emitter;
    }

    private void writeStream(SseEmitter emitter, String authorization, QaAskStreamRequest request) {
        try {
            Long userId = currentUserService.requireUserId(authorization);
            QaStreamDone done = qaService.askStream(userId, request.question(), request.history(),
                    chunk -> emitter.send(SseEmitter.event().name("message").data(chunk)));
            emitter.send(SseEmitter.event().name("done").data(done));
            emitter.complete();
        } catch (BusinessException exception) {
            completeWithReadableError(emitter, exception.errorCode().code(), exception.getMessage());
        } catch (IOException exception) {
            completeWithReadableError(emitter, ErrorCode.INTERNAL_ERROR.code(), "回答生成中断，请稍后重试。");
        } catch (Exception exception) {
            log.warn("qa stream failed", exception);
            completeWithReadableError(emitter, ErrorCode.INTERNAL_ERROR.code(), "系统内部错误，请稍后重试。");
        }
    }

    /** 业务/系统异常以 error 事件收尾，避免连接悬挂到超时 */
    private void completeWithReadableError(SseEmitter emitter, Object code, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("code", code, "message", message)));
        } catch (Exception ignored) {
            // 连接已断开时无需处理
        }
        emitter.complete();
    }
}
