package com.offerforge.common;

import com.offerforge.auth.CurrentUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求链路标识：为每个请求生成短 requestId 并尝试解析 userId，写入 MDC
 * 供日志模式 [requestId] [userId] [interviewId] 贯穿全链路。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_KEY = "requestId";
    public static final String USER_ID_KEY = "userId";

    private final CurrentUserService currentUserService;

    public RequestIdFilter(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            MDC.put(REQUEST_ID_KEY, UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            currentUserService.findUserId(request.getHeader("Authorization"))
                    .ifPresent(userId -> MDC.put(USER_ID_KEY, String.valueOf(userId)));
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
