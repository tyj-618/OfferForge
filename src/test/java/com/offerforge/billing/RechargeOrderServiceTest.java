package com.offerforge.billing;

import com.offerforge.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 充值订单：档位校验、待支付单幂等复用、开关关闭拒绝下单；
 * 状态机：PENDING→PAID 入账（幂等不重复入账），非待支付状态回调忽略；归属校验。
 */
class RechargeOrderServiceTest {

    private final RechargeOrderRepository orderRepository = mock(RechargeOrderRepository.class);
    private final WalletService walletService = mock(WalletService.class);
    private final BillingProperties properties = new BillingProperties();

    private final RechargeOrderService orderService =
            new RechargeOrderService(orderRepository, walletService, properties);

    private RechargeOrderServiceTest() {
        properties.setEnabled(true);
        properties.setProvider("mock");
        BillingProperties.PackageConfig pack = new BillingProperties.PackageConfig();
        pack.setId("pkg-10");
        pack.setName("¥10");
        pack.setAmountCents(1000);
        properties.setPackages(List.of(pack));
    }

    private RechargeOrder order(String orderNo, long userId, String status) {
        RechargeOrder order = new RechargeOrder();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setAmountCents(1000);
        order.setStatus(status);
        order.setProvider("mock");
        return order;
    }

    @Test
    void createOrderRejectsWhenBillingDisabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> orderService.createOrder(1L, "pkg-10"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("充值功能暂未开放");
    }

    @Test
    void createOrderRejectsUnknownPackage() {
        assertThatThrownBy(() -> orderService.createOrder(1L, "pkg-999"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("充值档位不存在");
    }

    @Test
    void createOrderReusesExistingPendingOrder() {
        RechargeOrder pending = order("OF001", 1L, RechargeOrder.STATUS_PENDING);
        when(orderRepository.findFirstByUserIdAndAmountCentsAndStatusOrderByIdDesc(
                1L, 1000L, RechargeOrder.STATUS_PENDING)).thenReturn(Optional.of(pending));

        assertThat(orderService.createOrder(1L, "pkg-10")).isSameAs(pending);
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void createOrderPersistsNewPendingOrderWithConfiguredAmount() {
        when(orderRepository.findFirstByUserIdAndAmountCentsAndStatusOrderByIdDesc(
                anyLong(), anyLong(), anyString())).thenReturn(Optional.empty());
        when(orderRepository.saveAndFlush(any(RechargeOrder.class))).thenAnswer(
                invocation -> invocation.getArgument(0));

        RechargeOrder created = orderService.createOrder(1L, "pkg-10");

        assertThat(created.getOrderNo()).startsWith("OF").hasSize(20);
        assertThat(created.getAmountCents()).isEqualTo(1000);
        assertThat(created.getStatus()).isEqualTo(RechargeOrder.STATUS_PENDING);
        assertThat(created.getProvider()).isEqualTo("mock");
    }

    @Test
    void markPaidTransitionsPendingToPaidAndRechargesWallet() {
        RechargeOrder pending = order("OF002", 1L, RechargeOrder.STATUS_PENDING);
        when(orderRepository.lockedByOrderNo("OF002")).thenReturn(Optional.of(pending));
        when(orderRepository.save(any(RechargeOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(orderService.markPaid("OF002", "MOCK-1")).isTrue();
        assertThat(pending.getStatus()).isEqualTo(RechargeOrder.STATUS_PAID);
        assertThat(pending.getProviderTxnId()).isEqualTo("MOCK-1");
        assertThat(pending.getPaidAt()).isNotNull();
        verify(walletService).recharge(1L, 1000L, "OF002");
    }

    @Test
    void markPaidIsIdempotentForAlreadyPaidOrder() {
        RechargeOrder paid = order("OF003", 1L, RechargeOrder.STATUS_PAID);
        when(orderRepository.lockedByOrderNo("OF003")).thenReturn(Optional.of(paid));

        assertThat(orderService.markPaid("OF003", "MOCK-2")).isFalse();
        // 重复回调不得二次入账
        verify(walletService, never()).recharge(anyLong(), anyLong(), anyString());
    }

    @Test
    void markPaidIgnoresCancelledOrder() {
        RechargeOrder cancelled = order("OF004", 1L, RechargeOrder.STATUS_CANCELLED);
        when(orderRepository.lockedByOrderNo("OF004")).thenReturn(Optional.of(cancelled));

        assertThat(orderService.markPaid("OF004", "MOCK-3")).isFalse();
        assertThat(cancelled.getStatus()).isEqualTo(RechargeOrder.STATUS_CANCELLED);
        verify(walletService, never()).recharge(anyLong(), anyLong(), anyString());
    }

    @Test
    void getOwnedEnforcesOwnership() {
        RechargeOrder order = order("OF005", 2L, RechargeOrder.STATUS_PENDING);
        when(orderRepository.findByOrderNo("OF005")).thenReturn(Optional.of(order));

        assertThat(orderService.getOwned(2L, "OF005")).isSameAs(order);
        assertThatThrownBy(() -> orderService.getOwned(1L, "OF005"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权限");
        assertThatThrownBy(() -> orderService.getOwned(1L, "OF-NONE"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("订单不存在");
    }
}
