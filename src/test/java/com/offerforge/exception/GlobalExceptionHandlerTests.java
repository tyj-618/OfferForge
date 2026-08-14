package com.offerforge.exception;

import com.offerforge.common.ApiResponse;
import com.offerforge.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常处理：业务码与 HTTP 状态映射。
 * 存量业务码保持 HTTP 200（前端按 body.code 分流），限流/不可用返回 429/503。
 */
class GlobalExceptionHandlerTests {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void aiUnavailableMapsTo503() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.AI_UNAVAILABLE, "AI 响应超时，请重试"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo(50300);
    }

    @Test
    void rateLimitedMapsTo429() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.TOO_MANY_REQUESTS));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().code()).isEqualTo(42900);
        assertThat(response.getBody().message()).isEqualTo("请求过于频繁，请稍后再试");
    }

    @Test
    void existingBusinessCodesKeepHttp200() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().code()).isEqualTo(40400);
    }

    @Test
    void dataAccessExceptionMapsTo503WithoutDetails() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleDataAccessException(
                new DataAccessResourceFailureException("connection refused"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo(50301);
        // 不向前端暴露底层技术细节
        assertThat(response.getBody().message()).doesNotContain("connection refused");
    }

    @Test
    void unexpectedExceptionMapsTo500WithoutDetails() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpectedException(
                new IllegalStateException("internal stack detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo(50000);
        assertThat(response.getBody().message()).doesNotContain("internal stack detail");
    }
}
