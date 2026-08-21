package com.offerforge.interview;

import com.offerforge.auth.CurrentUserService;
import com.offerforge.common.ApiResponse;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import com.offerforge.report.InterviewReport;
import com.offerforge.report.ReportService;
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
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/api/interview")
public class InterviewController {

    private static final Logger log = LoggerFactory.getLogger(InterviewController.class);
    /** MDC key：与日志模式中的 interviewId 占位对应 */
    private static final String INTERVIEW_ID_KEY = "interviewId";
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
        Long resumeId = request == null ? null : request.resumeId();
        String mode = request == null ? null : request.mode();
        java.util.List<String> categories = request == null ? null : request.categories();
        Boolean includeAlgorithm = request == null ? null : request.includeAlgorithm();
        String style = request == null ? null : request.style();
        return ApiResponse.success(interviewService.start(userId, position, resumeId, mode, categories, includeAlgorithm, style));
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
        MDC.put(INTERVIEW_ID_KEY, sessionId);
        try {
            Long userId = currentUserService.requireUserId(authorization);
            return ApiResponse.success(reportService.finishAndArchive(userId, sessionId));
        } finally {
            MDC.remove(INTERVIEW_ID_KEY);
        }
    }

    /**
     * 标记当前题「已掌握」（绿勾，不计分不入历史）：事件结构与 ask 一致，
     * message 事件为下一题话术，done 携带进度视图；仅训练模式出题阶段可用。
     */
    @PostMapping(value = "/{sessionId}/mastered", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter mastered(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @PathVariable String sessionId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        emitter.onTimeout(emitter::complete);
        emitter.onError(throwable -> emitter.complete());
        interviewStreamExecutor.execute(() -> writeChoiceTurn(
                emitter, authorization, sessionId, "mastered",
                interviewService::markMastered));
        return emitter;
    }

    /**
     * 标记当前题「不知道」（红叉）：等价作答「不知道」走完整评估反馈流程（强制 0 分），
     * 事件结构与 ask 一致；仅训练模式出题阶段可用。
     */
    @PostMapping(value = "/{sessionId}/dontknow", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter dontknow(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @PathVariable String sessionId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        emitter.onTimeout(emitter::complete);
        emitter.onError(throwable -> emitter.complete());
        interviewStreamExecutor.execute(() -> writeChoiceTurn(
                emitter, authorization, sessionId, "dontknow",
                interviewService::markDontKnow));
        return emitter;
    }

    /**
     * 训练模式“深度训练”：围绕当前知识点进入 DEEP_TRAINING 子流程并发出第 1 道递进题，
     * 事件结构与 ask 一致；非训练模式或非出题阶段走 error 事件。
     */
    @PostMapping(value = "/{sessionId}/deep-training", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter deepTraining(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable String sessionId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        emitter.onTimeout(emitter::complete);
        emitter.onError(throwable -> emitter.complete());
        interviewStreamExecutor.execute(() -> writeChoiceTurn(
                emitter, authorization, sessionId, "deep-training",
                interviewService::enterDeepTraining));
        return emitter;
    }

    /**
     * 退出深度训练：恢复主面试并出下一题，事件结构与 ask 一致；非深度训练状态走 error 事件。
     */
    @PostMapping(value = "/{sessionId}/deep-training/exit", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter deepTrainingExit(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable String sessionId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        emitter.onTimeout(emitter::complete);
        emitter.onError(throwable -> emitter.complete());
        interviewStreamExecutor.execute(() -> writeChoiceTurn(
                emitter, authorization, sessionId, "deep-training-exit",
                interviewService::exitDeepTraining));
        return emitter;
    }

    /**
     * 训练模式“下一板块”：用户主动切换到下一题/下一阶段，事件结构与 ask 一致。
     */
    @PostMapping(value = "/{sessionId}/next-question", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter nextQuestion(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable String sessionId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        emitter.onTimeout(emitter::complete);
        emitter.onError(throwable -> emitter.complete());
        interviewStreamExecutor.execute(() -> writeChoiceTurn(
                emitter, authorization, sessionId, "next-question",
                interviewService::chooseNextQuestion));
        return emitter;
    }

    /**
     * 暂存续考（任务 4）：查询当前用户未结束的面试会话，供开始卡片展示「继续未完成的面试」；
     * 无进行中会话时 data 为 null。
     */
    @GetMapping("/active-session")
    public ApiResponse<InterviewStatusResponse> activeSession(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(interviewService.activeSession(userId));
    }

    @GetMapping("/{sessionId}/status")
    public ApiResponse<InterviewStatusResponse> status(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String sessionId) {
        MDC.put(INTERVIEW_ID_KEY, sessionId);
        try {
            Long userId = currentUserService.requireUserId(authorization);
            return ApiResponse.success(interviewService.status(userId, sessionId));
        } finally {
            MDC.remove(INTERVIEW_ID_KEY);
        }
    }

    private void writeTurn(SseEmitter emitter, String authorization, String sessionId, String message) {
        // SSE 在独立线程执行，过滤器 MDC 不会传播，此处重新写入 interviewId
        MDC.put(INTERVIEW_ID_KEY, sessionId);
        try {
            Long userId = currentUserService.requireUserId(authorization);
            validateMessage(message);
            InterviewTurnResult result = interviewService.answer(userId, sessionId, message, sseSink(emitter));
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
        } finally {
            MDC.remove(INTERVIEW_ID_KEY);
        }
    }

    /**
     * 无请求体的流式回合统一模板（mastered / dontknow / deep-training / deep-training/exit / next-question）：
     * 鉴权后委托服务层执行，业务/IO/运行时异常均以 error 事件收尾，避免连接悬挂到超时。
     */
    private void writeChoiceTurn(SseEmitter emitter, String authorization, String sessionId, String turnName,
                                 ChoiceTurnAction action) {
        MDC.put(INTERVIEW_ID_KEY, sessionId);
        try {
            Long userId = currentUserService.requireUserId(authorization);
            InterviewTurnResult result = action.execute(userId, sessionId, sseSink(emitter));
            emitter.send(SseEmitter.event().name("done").data(result));
            emitter.complete();
        } catch (BusinessException exception) {
            completeWithReadableError(emitter, exception.errorCode().code(), exception.getMessage());
        } catch (IOException exception) {
            completeWithReadableError(emitter, ErrorCode.INTERNAL_ERROR.code(), "面试对话流已中断，请稍后重试。");
        } catch (Exception exception) {
            log.error("interview {} unexpected error sessionId={}", turnName, sessionId, exception);
            completeWithReadableError(emitter, ErrorCode.INTERNAL_ERROR.code(), "系统内部错误，请稍后重试。");
        } finally {
            MDC.remove(INTERVIEW_ID_KEY);
        }
    }

    /** 选择类回合执行体：鉴权后的 userId + 会话 ID + 流式输出槽，返回本轮结果 */
    @FunctionalInterface
    private interface ChoiceTurnAction {
        InterviewTurnResult execute(Long userId, String sessionId, InterviewStreamSink sink) throws IOException;
    }

    /**
     * SSE 输出槽：message 为对话内容帧，segment 标记新气泡，progress 为阶段状态提示。
     */
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
