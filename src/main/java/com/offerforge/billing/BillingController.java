package com.offerforge.billing;

import com.offerforge.auth.CurrentUserService;
import com.offerforge.common.ApiResponse;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 付费充值接口：钱包状态/充值档位/模型价目/下单/订单查询/流水/支付回调桩。
 * 总开关关闭时 status 正常返回（前端据此隐藏入口），其余写操作一律拒绝。
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private static final Logger log = LoggerFactory.getLogger(BillingController.class);
    private static final int LIST_LIMIT = 50;

    private final BillingProperties properties;
    private final WalletService walletService;
    private final RechargeOrderService orderService;
    private final PaymentProvider paymentProvider;
    private final CurrentUserService currentUserService;

    /** 钱包与开关状态：前端导航/引导按钮据此呈现 */
    public record BillingStatus(boolean enabled, String provider, long balanceCents) {
    }

    /** 充值档位视图 */
    public record PackageView(String id, String name, long amountCents) {
    }

    /** 模型价目视图（价格为分/百万 token）；defaultModel 为免费额度使用的系统默认模型 */
    public record ModelView(String id, String name, long inputPerMillionCents,
                            long outputPerMillionCents, boolean paidOnly) {
    }

    /** 下单请求：仅接受配置档位 id，金额不可由客户端指定 */
    public record CreateOrderRequest(String packageId) {
    }

    /** 下单响应：附带渠道预支付提示（mock 渠道为模拟支付说明文案） */
    public record CreateOrderView(String orderNo, long amountCents, String status, String payHint) {
    }

    /** 订单视图 */
    public record OrderView(String orderNo, long amountCents, String status, String provider,
                            String providerTxnId, LocalDateTime createdAt, LocalDateTime paidAt) {
    }

    /** 流水视图 */
    public record TransactionView(String type, long amountCents, long balanceAfterCents,
                                  String refNo, String detail, LocalDateTime createdAt) {
    }

    public BillingController(BillingProperties properties,
                             WalletService walletService,
                             RechargeOrderService orderService,
                             PaymentProvider paymentProvider,
                             CurrentUserService currentUserService) {
        this.properties = properties;
        this.walletService = walletService;
        this.orderService = orderService;
        this.paymentProvider = paymentProvider;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/status")
    public ApiResponse<BillingStatus> status(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(new BillingStatus(
                properties.isEnabled(), properties.getProvider(), walletService.balance(userId)));
    }

    @GetMapping("/packages")
    public ApiResponse<List<PackageView>> packages(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        currentUserService.requireUserId(authorization);
        return ApiResponse.success(properties.getPackages().stream()
                .map(pack -> new PackageView(pack.getId(), pack.getName(), pack.getAmountCents()))
                .toList());
    }

    @GetMapping("/models")
    public ApiResponse<List<ModelView>> models(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        currentUserService.requireUserId(authorization);
        return ApiResponse.success(properties.getModels().stream()
                .map(model -> new ModelView(model.getId(), model.getName(),
                        model.getInputPerMillionCents(), model.getOutputPerMillionCents(), model.isPaidOnly()))
                .toList());
    }

    @PostMapping("/orders")
    public ApiResponse<CreateOrderView> createOrder(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody CreateOrderRequest request) {
        Long userId = currentUserService.requireUserId(authorization);
        if (request == null || request.packageId() == null || request.packageId().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择充值档位");
        }
        RechargeOrder order = orderService.createOrder(userId, request.packageId().trim());
        String payHint = paymentProvider.prepay(order);
        return ApiResponse.success(new CreateOrderView(
                order.getOrderNo(), order.getAmountCents(), order.getStatus(), payHint));
    }

    @GetMapping("/orders/{orderNo}")
    public ApiResponse<OrderView> order(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String orderNo) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(toView(orderService.getOwned(userId, orderNo)));
    }

    @GetMapping("/orders")
    public ApiResponse<List<OrderView>> orders(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(orderService.list(userId, LIST_LIMIT).stream().map(this::toView).toList());
    }

    @GetMapping("/transactions")
    public ApiResponse<List<TransactionView>> transactions(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(walletService.transactions(userId, LIST_LIMIT).stream()
                .map(transaction -> new TransactionView(transaction.getType(), transaction.getAmountCents(),
                        transaction.getBalanceAfterCents(), transaction.getRefNo(),
                        transaction.getDetail(), transaction.getCreatedAt()))
                .toList());
    }

    /**
     * 支付回调桩：mock 渠道按 orderNo 确认到账（联调用）；
     * wechat 渠道审核通过后在此接入验签与到账确认，当前未验签一律不处理。
     */
    @PostMapping("/notify")
    public ApiResponse<Map<String, Boolean>> notify(@RequestBody(required = false) Map<String, String> payload) {
        if (!"mock".equals(paymentProvider.name())) {
            // TODO 微信支付回调：验签 → markPaid；验签未接入前不处理任何通知
            log.warn("billing notify received while provider={}, ignored", paymentProvider.name());
            return ApiResponse.success(Map.of("accepted", false));
        }
        String orderNo = payload == null ? null : payload.get("orderNo");
        if (orderNo == null || orderNo.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "缺少订单号");
        }
        boolean paid = orderService.markPaid(orderNo.trim(), "MOCK-" + System.currentTimeMillis());
        return ApiResponse.success(Map.of("accepted", paid));
    }

    private OrderView toView(RechargeOrder order) {
        return new OrderView(order.getOrderNo(), order.getAmountCents(), order.getStatus(),
                order.getProvider(), order.getProviderTxnId(), order.getCreatedAt(), order.getPaidAt());
    }
}
