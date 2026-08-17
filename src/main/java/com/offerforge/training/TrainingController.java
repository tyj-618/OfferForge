package com.offerforge.training;

import com.offerforge.auth.CurrentUserService;
import com.offerforge.common.ApiResponse;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import com.offerforge.interview.InterviewStreamSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 专项训练（任务 7）：SSE 契约复用面试的 message/segment/progress/done/error 事件结构。
 */
@RestController
@RequestMapping("/api/training")
public class TrainingController {

    private static final Logger log = LoggerFactory.getLogger(TrainingController.class);
    /** MDC key：与日志模式中的 interviewId 占位对应 */
    private static final String SESSION_ID_KEY = "interviewId";
    /** 与面试流式同预算：评分重试 + 流式读超时的最坏覆盖 */
    private static final long STREAM_TIMEOUT_MILLIS = 180_000L;

    private final TrainingService trainingService;
    private final CurrentUserService currentUserService;
    private final Executor interviewStreamExecutor;

    public TrainingController(TrainingService trainingService,
                              CurrentUserService currentUserService,
                              @Qualifier("interviewStreamExecutor") Executor interviewStreamExecutor) {
        this.trainingService = trainingService;
        this.currentUserService = currentUserService;
        this.interviewStreamExecutor = interviewStreamExecutor;
    }

    @PostMapping("/start")
    public ApiResponse<TrainingStartResponse> start(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) TrainingStartRequest request) {
        Long userId = currentUserService.requireUserId(authorization);
        String category = request == null ? null : request.category();
        return ApiResponse.success(trainingService.start(userId, category));
    }

    /**
     * SSE 流式作答：message 为话术分块，segment 标记新气泡，progress 为阶段提示，
     * done 携带评分/点评/进度/详细评估，error 携带 code/message。
     */
    @PostMapping(value = "/{sessionId}/answer", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter answer(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @PathVariable String sessionId,
                             @RequestBody TrainingAskRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        emitter.onTimeout(emitter::complete);
        emitter.onError(throwable -> emitter.complete());
        interviewStreamExecutor.execute(() -> writeTurn(emitter, authorization, sessionId, request.message()));
        return emitter;
    }

    @GetMapping("/{sessionId}/status")
    public ApiResponse<TrainingStatusResponse> status(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String sessionId) {
        MDC.put(SESSION_ID_KEY, sessionId);
        try {
            Long userId = currentUserService.requireUserId(authorization);
            return ApiResponse.success(trainingService.status(userId, sessionId));
        } finally {
            MDC.remove(SESSION_ID_KEY);
        }
    }

    /**
     * 主动结束训练：进行中则归档已作答成绩；已完成幂等返回进度视图。
     */
    @PostMapping("/{sessionId}/finish")
    public ApiResponse<TrainingStatusResponse> finish(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String sessionId) {
        MDC.put(SESSION_ID_KEY, sessionId);
        try {
            Long userId = currentUserService.requireUserId(authorization);
            return ApiResponse.success(trainingService.finishEarly(userId, sessionId));
        } finally {
            MDC.remove(SESSION_ID_KEY);
        }
    }

    /** 我的训练历史（简要成绩归档列表） */
    @GetMapping("/records")
    public ApiResponse<List<TrainingRecordView>> records(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(trainingService.records(userId));
    }

    private void writeTurn(SseEmitter emitter, String authorization, String sessionId, String message) {
        // SSE 在独立线程执行，过滤器 MDC 不会传播，此处重新写入会话 id
        MDC.put(SESSION_ID_KEY, sessionId);
        try {
            Long userId = currentUserService.requireUserId(authorization);
            validateMessage(message);
            TrainingTurnResult result = trainingService.answer(userId, sessionId, message, sseSink(emitter));
            emitter.send(SseEmitter.event().name("done").data(result));
            emitter.complete();
        } catch (BusinessException exception) {
            completeWithReadableError(emitter, exception.errorCode().code(), exception.getMessage());
        } catch (IOException exception) {
            completeWithReadableError(emitter, ErrorCode.INTERNAL_ERROR.code(), "训练对话流已中断，请稍后重试。");
        } catch (Exception exception) {
            // 兜底：模型评分解析、Redis 访问等运行时异常也必须以 error 事件收尾，否则连接会悬挂到超时
            log.error("training stream unexpected error sessionId={}", sessionId, exception);
            completeWithReadableError(emitter, ErrorCode.INTERNAL_ERROR.code(), "系统内部错误，请稍后重试。");
        } finally {
            MDC.remove(SESSION_ID_KEY);
        }
    }

    private static InterviewStreamSink sseSink(SseEmitter emitter) {
        return new InterviewStreamSink() {
            @Override
            public void chunk(String text) throws IOException {
                emitter.send(SseEmitter.event().name("message").data(text));
            }

            @Override
            public void segment() throws IOException {
                emitter.send(SseEmitter.event().name("segment").data(""));
            }

            @Override
            public void progress(String text) throws IOException {
                emitter.send(SseEmitter.event().name("progress").data(text));
            }
        };
    }

    private void validateMessage(String message) {
        if (message == null || message.isBlank() || message.length() > 2000) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "回答内容不能为空且不超过 2000 字");
        }
    }

    private void completeWithReadableError(SseEmitter emitter, int code, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(new StreamError(code, message)));
        } catch (IOException | IllegalStateException ignored) {
            // 客户端可能已断开或 emitter 已超时结束，下方统一收尾
        }
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // emitter 已被超时回调结束
        }
    }

    private record StreamError(int code, String message) {
    }
}
