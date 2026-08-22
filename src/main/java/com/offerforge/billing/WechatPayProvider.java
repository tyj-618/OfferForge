package com.offerforge.billing;

import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 微信支付渠道骨架：商户资质审核通过后，在此按微信支付 v3 规范实现
 * 统一下单（prepay）与回调验签，并配置商户凭据环境变量即可启用。
 * 当前仅占位装配，调用即提示渠道未就绪。
 */
@Component
@ConditionalOnProperty(name = "offerforge.billing.provider", havingValue = "wechat")
public class WechatPayProvider implements PaymentProvider {

    @Override
    public String name() {
        return "wechat";
    }

    @Override
    public String prepay(RechargeOrder order) {
        // TODO 审核通过后接入：统一下单 → 返回 Native 支付二维码链接；回调验签在 /api/billing/notify 处理
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "微信支付渠道尚未就绪");
    }
}
