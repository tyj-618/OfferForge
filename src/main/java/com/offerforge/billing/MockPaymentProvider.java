package com.offerforge.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 模拟支付渠道（支付资质审核期间的联调实现）：预支付仅返回提示文案，
 * 由模拟支付接口确认到账；不产生任何真实资金流。
 */
@Component
@ConditionalOnProperty(name = "offerforge.billing.provider", havingValue = "mock", matchIfMissing = true)
public class MockPaymentProvider implements PaymentProvider {

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public String prepay(RechargeOrder order) {
        return "模拟支付：请调用模拟支付接口完成付款（支付渠道审核中，无真实扣款）";
    }
}
