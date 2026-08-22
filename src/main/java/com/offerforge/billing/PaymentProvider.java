package com.offerforge.billing;

/**
 * 支付渠道抽象：审核通过前仅装配 Mock 实现；真实渠道（如微信支付）
 * 按渠道规范实现预支付与回调验签后，切换 offerforge.billing.provider 即可。
 */
public interface PaymentProvider {

    /** 渠道标识：mock / wechat */
    String name();

    /**
     * 发起预支付：返回供前端展示的支付凭证描述（真实渠道为二维码内容/跳转链接）。
     * Mock 实现仅返回提示文案，支付确认走模拟支付接口。
     */
    String prepay(RechargeOrder order);
}
