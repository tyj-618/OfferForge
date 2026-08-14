package com.offerforge.interview;

import com.offerforge.auth.CurrentUserService;
import com.offerforge.common.ApiResponse;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import com.offerforge.report.InterviewReport;
import com.offerforge.report.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/api/interview")
public class InterviewController {

    private static final Logger log = LoggerFactory.getLogger(InterviewController.class);
    /** 需覆盖最坏上游耗时：评分重试（30s×2）+ 退避 + 流式读超时（60s），120s 预算过紧 */
    private static final long STREAM_TIMEOUT_MILLIS = 180_000L;

    private final InterviewService interviewService;
    private final ReportService reportService;
    private final CurrentUserService currentUserService;
    private final Executor interviewStreamExecutor;

    public InterviewController(InterviewService interviewService,
                               ReportService reportService,
                               CurrentUserService currentUserService,
                               @Qualifier("interviewStreamExecutor") Executor interviewStreamExecutor) {
        this.interviewService = interviewService;
        this.reportService = reportService;
        this.currentUserService = currentUserService;
        this.interviewStreamExecutor = interviewStreamExecutor;
    }

    @PostMapping("/start")
    public ApiResponse<InterviewStartResponse> start(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) InterviewStartRequest request) {
        Long userId = currentUserService.requireUserId(authorization);
        String position = request == null ? null : request.position();
        return ApiResponse.success(interviewService.start(userId, position));
    }

    /**
     * SSE 流式应答：message 事件为话术分块，done 事件携带评分/转移动作/进度视图，
     * error 事件携带 code/message（登录态/校验/业务错误均走事件流，避免与 text/event-stream 内容协商冲突）。
     */
    @PostMapping(value = "/{sessionId}/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestHeader(value = "Authorization", required = false) String authorization,
                          @PathVariable String sessionId,
                          @RequestBody InterviewAskRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        // 超时/异常时及时结束 emitter，避免异步任务对已失效连接的无效写入
        emitter.onTimeout(emitter::complete);
        emitter.onError(throwable -> emitter.complete());
        interviewStreamExecutor.execute(() -> writeTurn(emitter, authorization, sessionId, request.message()));
        return emitter;
    }

    /**
     * 结束面试：置终态 + 生成综合反馈报告 + 归档；幂等，重复调用返回既有报告。
     */
    @PostMapping("/{sessionId}/finish")
    public ApiResponse<InterviewReport> finish(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String sessionId) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(reportService.finishAndArchive(userId, sessionId));
    }

    @GetMapping("/{sessionId}/status")
    public ApiResponse<InterviewStatusResponse> status(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String sessionId) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(interviewService.status(userId, sessionId));
    }

    private void writeTurn(SseEmitter emitter, String authorization, String sessionId, String message) {
        try {
            Long userId = currentUserService.requireUserId(authorization);
            validateMessage(message);
            InterviewTurnResult result = interviewService.answer(
                    userId,
                    sessionId,
                    message,
                    chunk -> emitter.send(SseEmitter.event().name("message").data(chunk)));
            emitter.send(SseEmitter.event().name("done").data(result));
            emitter.complete();
        } catch (BusinessException exception) {
            completeWithReadableError(emitter, exception.errorCode().code(), exception.getMessage());
        } catch (IOException exception) {
            completeWithReadableError(emitter, ErrorCode.INTERNAL_ERROR.code(), "面试对话流已中断，请稍后重试。");
        } catch (Exception exception) {
            // 兜底：模型评分解析、Redis 访问等运行时异常也必须以 error 事件收尾，否则连接会悬挂到超时
            log.error("interview stream unexpected error sessionId={}", sessionId, exception);
            completeWithReadableError(emitter, ErrorCode.INTERNAL_ERROR.code(), "系统内部错误，请稍后重试。");
        }
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
