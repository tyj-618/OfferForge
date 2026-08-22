package com.offerforge.ratelimit;

import com.offerforge.auth.CurrentUserService;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 按用户限流拦截器：滑动窗口，超限返回 42900。
 * 未携带有效 token 的请求不限流（后续控制器会以 40100 拒绝）。
 * SSE 端点（面试作答）超限时以 event:error 事件流返回，与控制器内错误格式一致；
 * 其余端点抛 BusinessException 由全局异常处理返回 JSON 429。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimiter rateLimiter;
    private final CurrentUserService currentUserService;
    private final int interviewAskLimit;
    private final int qaAskLimit;
    private final int reportLimit;
    private final long windowMillis;

    public RateLimitInterceptor(RateLimiter rateLimiter,
                                CurrentUserService currentUserService,
                                @Value("${offerforge.rate-limit.interview-ask-limit:10}") int interviewAskLimit,
                                @Value("${offerforge.rate-limit.qa-ask-limit:5}") int qaAskLimit,
                                @Value("${offerforge.rate-limit.report-limit:60}") int reportLimit,
                                @Value("${offerforge.rate-limit.window-millis:60000}") long windowMillis) {
        this.rateLimiter = rateLimiter;
        this.currentUserService = currentUserService;
        this.interviewAskLimit = interviewAskLimit;
        this.qaAskLimit = qaAskLimit;
        this.reportLimit = reportLimit;
        this.windowMillis = windowMillis;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // SSE 异步完成后的 re-dispatch 会重走拦截器链，仅对原始请求计数，避免重复消耗配额
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        Route route = match(request);
        if (route == null) {
            return true;
        }
        Optional<Long> userId = currentUserService.findUserId(request.getHeader("Authorization"));
        if (userId.isEmpty()) {
            return true;
        }
        boolean allowed = rateLimiter.tryAcquire(userId.get() + ":" + route.key(), route.limit(), windowMillis);
        if (!allowed) {
            if (route.sse()) {
                writeSseRateLimited(response);
                return false;
            }
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
        return true;
    }

    /** SSE 端点超限：HTTP 429 + event:error 事件（客户端按事件流解析错误码） */
    private void writeSseRateLimited(HttpServletResponse response) {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            response.getWriter().write("event:error\ndata:{\"code\":42900,\"message\":\"请求过于频繁，请稍后再试\"}\n\n");
            response.getWriter().flush();
        } catch (IOException exception) {
            log.warn("rate limit sse response write failed: {}", exception.getMessage());
        }
    }

    private Route match(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        if ("POST".equalsIgnoreCase(method) && uri.matches("/api/interview/[^/]+/ask")) {
            return new Route("interview-ask", interviewAskLimit, true);
        }
        // 专项训练作答与面试作答同为重 LLM 开销的 SSE 端点，共用限额（任务 7）
        if ("POST".equalsIgnoreCase(method) && uri.matches("/api/training/[^/]+/answer")) {
            return new Route("training-answer", interviewAskLimit, true);
        }
        if ("POST".equalsIgnoreCase(method) && uri.equals("/api/qa/ask")) {
            return new Route("qa-ask", qaAskLimit, false);
        }
        // 充值下单：复用 qa 限额，防刷单堆积待支付订单（付费计费）
        if ("POST".equalsIgnoreCase(method) && uri.equals("/api/billing/orders")) {
            return new Route("billing-order", qaAskLimit, false);
        }
        // 快捷提问流式端点：同为重 LLM 开销，共用 qa 限额；429 需以 SSE error 帧下发
        if ("POST".equalsIgnoreCase(method) && uri.equals("/api/qa/ask-stream")) {
            return new Route("qa-ask", qaAskLimit, true);
        }
        // 仅报告详情限流；history/progress 是页面加载必需的纯 DB 分页读，不限流
        if ("GET".equalsIgnoreCase(method) && uri.matches("/api/report/(?!history$|progress$)[^/]+")) {
            return new Route("report", reportLimit, false);
        }
        return null;
    }

    private record Route(String key, int limit, boolean sse) {
    }
}
