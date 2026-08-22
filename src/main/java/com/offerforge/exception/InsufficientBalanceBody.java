package com.offerforge.exception;

/**
 * 余额不足专用响应体：字符串业务码 + 当前余额（分），
 * 前端按 code=INSUFFICIENT_BALANCE 引导前往充值中心。
 */
public record InsufficientBalanceBody(String code, String message, long balanceCents) {

    public static final String CODE = "INSUFFICIENT_BALANCE";

    public static InsufficientBalanceBody of(long balanceCents, String message) {
        return new InsufficientBalanceBody(CODE, message, balanceCents);
    }
}
