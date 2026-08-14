package com.offerforge.exception;

import com.offerforge.common.ApiResponse;
import com.offerforge.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务码 + HTTP 状态双重表达。
 * 存量业务码保持 HTTP 200（前端拦截器按 body.code 分流），
 * 限流/不可用类新增码同步返回 429/503，便于网关与监控识别。
 * 未知异常记录完整堆栈，不向前端暴露技术细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        log.warn("business error code={} message={}", exception.errorCode().code(), exception.getMessage());
        return ResponseEntity.status(httpStatus(exception.errorCode()))
                .body(ApiResponse.fail(exception.errorCode().code(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValidationException(MethodArgumentNotValidException exception) {
        FieldError fieldError = exception.getBindingResult().getFieldError();
        String message = fieldError == null
                ? ErrorCode.PARAM_ERROR.message()
                : "参数 " + fieldError.getField() + " " + fieldError.getDefaultMessage();
        return ApiResponse.fail(ErrorCode.PARAM_ERROR.code(), message);
    }

    /** 数据库访问失败：返回 503；进行中的面试上下文在 Redis/内存，不受影响 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccessException(DataAccessException exception) {
        log.error("data access error", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(ErrorCode.SERVICE_UNAVAILABLE.code(), ErrorCode.SERVICE_UNAVAILABLE.message()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("unexpected error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR.code(), ErrorCode.INTERNAL_ERROR.message()));
    }

    private HttpStatus httpStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case AI_UNAVAILABLE, SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.OK;
        };
    }
}
