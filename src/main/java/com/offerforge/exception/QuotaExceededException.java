package com.offerforge.exception;

/**
 * 免费额度耗尽异常：全局处理器返回 HTTP 429 + QUOTA_EXCEEDED 专用响应体。
 */
public class QuotaExceededException extends RuntimeException {

    private final int remainingQuota;

    public QuotaExceededException(int remainingQuota) {
        super("今日免费额度已用完");
        this.remainingQuota = remainingQuota;
    }

    public int remainingQuota() {
        return remainingQuota;
    }
}
