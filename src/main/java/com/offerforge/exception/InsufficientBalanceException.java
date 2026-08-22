package com.offerforge.exception;

/**
 * 计费模式余额不足：免费额度耗尽后走充值余额的用户，余额不足以继续服务时抛出。
 * 前端按 HTTP 402 + 业务码引导充值。
 */
public class InsufficientBalanceException extends RuntimeException {

    private final long balanceCents;

    public InsufficientBalanceException(long balanceCents) {
        super("余额不足，请先充值");
        this.balanceCents = balanceCents;
    }

    public long balanceCents() {
        return balanceCents;
    }
}
