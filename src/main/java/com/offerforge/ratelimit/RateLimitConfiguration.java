package com.offerforge.ratelimit;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册限流拦截器：覆盖面试作答、问答与报告查询接口。
 */
@Configuration
public class RateLimitConfiguration implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    public RateLimitConfiguration(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/interview/*/ask", "/api/training/*/answer", "/api/qa/ask", "/api/qa/ask-stream", "/api/report/**");
    }
}
