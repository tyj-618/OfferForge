package com.offerforge.billing;

import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 充值订单服务：下单（档位校验 + 待支付单幂等复用）→ 渠道确认到账（状态机 + 钱包入账）。
 * 订单金额一律取自配置档位，请求体不可直接指定金额，防篡改。
 */
@Service
public class RechargeOrderService {

    private static final Logger log = LoggerFactory.getLogger(RechargeOrderService.class);
    private static final DateTimeFormatter ORDER_NO_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss");
    private static final int ORDER_NO_MAX_ATTEMPTS = 3;

    private final RechargeOrderRepository orderRepository;
    private final WalletService walletService;
    private final BillingProperties properties;

    public RechargeOrderService(RechargeOrderRepository orderRepository,
                                WalletService walletService,
                                BillingProperties properties) {
        this.orderRepository = orderRepository;
        this.walletService = walletService;
        this.properties = properties;
    }

    /**
     * 创建充值订单：同用户同金额存在待支付单时直接复用；
     * 返回订单实体（渠道预支付文案由控制层向渠道索取）。
     */
    public RechargeOrder createOrder(Long userId, String packageId) {
        requireEnabled();
        BillingProperties.PackageConfig pack = properties.getPackages().stream()
                .filter(candidate -> candidate.getId() != null && candidate.getId().equals(packageId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "充值档位不存在"));
        return orderRepository.findFirstByUserIdAndAmountCentsAndStatusOrderByIdDesc(
                        userId, pack.getAmountCents(), RechargeOrder.STATUS_PENDING)
                .orElseGet(() -> persistNewOrder(userId, pack.getAmountCents()));
    }

    /**
     * 确认到账：PENDING → PAID（幂等），同事务内钱包入账；
     * 非待支付状态重复回调直接忽略（渠道可能重复通知）。
     */
    @Transactional
    public boolean markPaid(String orderNo, String providerTxnId) {
        RechargeOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单不存在"));
        if (RechargeOrder.STATUS_PAID.equals(order.getStatus())) {
            return false;
        }
        if (!RechargeOrder.STATUS_PENDING.equals(order.getStatus())) {
            log.warn("recharge order not payable orderNo={} status={}", orderNo, order.getStatus());
            return false;
        }
        order.setStatus(RechargeOrder.STATUS_PAID);
        order.setPaidAt(LocalDateTime.now());
        order.setProviderTxnId(providerTxnId);
        orderRepository.save(order);
        walletService.recharge(order.getUserId(), order.getAmountCents(), order.getOrderNo());
        log.info("recharge order paid orderNo={} userId={} amountCents={}",
                orderNo, order.getUserId(), order.getAmountCents());
        return true;
    }

    /** 查询本人订单（越权防护：订单归属校验） */
    public RechargeOrder getOwned(Long userId, String orderNo) {
        RechargeOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单不存在"));
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限查看该订单");
        }
        return order;
    }

    public List<RechargeOrder> list(Long userId, int limit) {
        return orderRepository.findByUserIdOrderByIdDesc(userId, PageRequest.of(0, limit));
    }

    private RechargeOrder persistNewOrder(Long userId, long amountCents) {
        for (int attempt = 0; attempt < ORDER_NO_MAX_ATTEMPTS; attempt++) {
            RechargeOrder order = new RechargeOrder();
            order.setOrderNo(generateOrderNo());
            order.setUserId(userId);
            order.setAmountCents(amountCents);
            order.setStatus(RechargeOrder.STATUS_PENDING);
            order.setProvider(properties.getProvider());
            try {
                RechargeOrder saved = orderRepository.saveAndFlush(order);
                log.info("recharge order created orderNo={} userId={} amountCents={}",
                        saved.getOrderNo(), userId, amountCents);
                return saved;
            } catch (DataIntegrityViolationException exception) {
                // 订单号碰撞（概率极低）：换号重试
            }
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "订单创建失败，请重试");
    }

    /** 订单号：OF + 年月日时分秒 + 6 位随机数（无敏感信息，可安全展示） */
    private String generateOrderNo() {
        return "OF" + LocalDateTime.now().format(ORDER_NO_TIME)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "充值功能暂未开放");
        }
    }
}
