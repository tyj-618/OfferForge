package com.offerforge.billing;

import com.offerforge.auth.CurrentUserService;
import com.offerforge.common.ApiResponse;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 模拟支付确认（仅 provider=mock 时装配）：支付资质审核期间的联调入口，
 * 登录用户确认支付本人名下的待支付订单，无真实资金流；
 * 切换 wechat 渠道后该端点整体消失，不留攻击面。
 */
@RestController
@RequestMapping("/api/billing")
@ConditionalOnProperty(name = "offerforge.billing.provider", havingValue = "mock", matchIfMissing = true)
public class BillingMockPayController {

    private final BillingProperties properties;
    private final RechargeOrderService orderService;
    private final CurrentUserService currentUserService;

    public BillingMockPayController(BillingProperties properties,
                                    RechargeOrderService orderService,
                                    CurrentUserService currentUserService) {
        this.properties = properties;
        this.orderService = orderService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/mock-pay/{orderNo}")
    public ApiResponse<Map<String, Boolean>> mockPay(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String orderNo) {
        Long userId = currentUserService.requireUserId(authorization);
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "充值功能暂未开放");
        }
        // 归属校验：只能模拟支付本人订单
        orderService.getOwned(userId, orderNo);
        boolean paid = orderService.markPaid(orderNo, "MOCK-" + System.currentTimeMillis());
        return ApiResponse.success(Map.of("paid", paid));
    }
}
