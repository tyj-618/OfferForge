package com.offerforge.exception;

/**
 * 额度耗尽专用响应体：字符串业务码 + 剩余次数，与 ApiResponse 数字码体系区分，
 * 前端按 code=QUOTA_EXCEEDED 引导配置自带 Key。
 */
public record QuotaExceededBody(String code, String message, int remainingQuota) {

    public static final String CODE = "QUOTA_EXCEEDED";

    public static QuotaExceededBody of(int remainingQuota, String message) {
        return new QuotaExceededBody(CODE, message, remainingQuota);
    }
}
