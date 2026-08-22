package com.offerforge.billing;

import com.offerforge.apikey.ApiKeyService;
import com.offerforge.exception.BusinessException;
import com.offerforge.exception.InsufficientBalanceException;
import com.offerforge.exception.QuotaExceededException;
import com.offerforge.quota.QuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开局准入链：自带 Key → 免费额度 → 充值余额计费 → 拒绝；
 * 付费模型强制计费模式且要求余额；未知模型拒绝。
 */
class BillingAccessServiceTest {

    private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
    private final QuotaService quotaService = mock(QuotaService.class);
    private final WalletService walletService = mock(WalletService.class);
    private final BillingProperties properties = new BillingProperties();

    private final BillingAccessService accessService =
            new BillingAccessService(apiKeyService, quotaService, walletService, properties);

    @BeforeEach
    void setUp() {
        BillingProperties.ModelConfig free = new BillingProperties.ModelConfig();
        free.setId("test-free");
        free.setPaidOnly(false);
        BillingProperties.ModelConfig paid = new BillingProperties.ModelConfig();
        paid.setId("test-paid");
        paid.setPaidOnly(true);
        properties.setModels(List.of(free, paid));
    }

    @Test
    void ownKeyTakesPrecedenceWithoutQuotaOrBilling() {
        when(apiKeyService.hasKey(1L)).thenReturn(true);

        BillingAccessService.Decision decision = accessService.decide(1L, null);

        assertThat(decision.keySource()).isEqualTo("user");
        assertThat(decision.billable()).isFalse();
        verify(quotaService, never()).consumeQuota(1L);
        verify(walletService, never()).canBill(1L);
    }

    @Test
    void freeQuotaAvailableMeansSystemKeyWithoutBilling() {
        when(apiKeyService.hasKey(1L)).thenReturn(false);
        when(quotaService.isEnabled()).thenReturn(true);
        when(quotaService.consumeQuota(1L)).thenReturn(true);

        BillingAccessService.Decision decision = accessService.decide(1L, null);

        assertThat(decision.keySource()).isEqualTo("system");
        assertThat(decision.billable()).isFalse();
    }

    @Test
    void quotaExhaustedWithBalanceSwitchesToBillingMode() {
        when(apiKeyService.hasKey(1L)).thenReturn(false);
        when(quotaService.isEnabled()).thenReturn(true);
        when(quotaService.consumeQuota(1L)).thenReturn(false);
        when(walletService.canBill(1L)).thenReturn(true);

        BillingAccessService.Decision decision = accessService.decide(1L, null);

        assertThat(decision.keySource()).isEqualTo("system");
        assertThat(decision.billable()).isTrue();
    }

    @Test
    void quotaExhaustedWithoutBalanceThrowsQuotaExceeded() {
        when(apiKeyService.hasKey(1L)).thenReturn(false);
        when(quotaService.isEnabled()).thenReturn(true);
        when(quotaService.consumeQuota(1L)).thenReturn(false);
        when(walletService.canBill(1L)).thenReturn(false);
        when(quotaService.checkQuota(1L)).thenReturn(0);

        assertThatThrownBy(() -> accessService.decide(1L, null))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void paidOnlyModelForcesBillingModeWhenBalanceAvailable() {
        properties.setEnabled(true);
        when(apiKeyService.hasKey(1L)).thenReturn(false);
        when(walletService.canBill(1L)).thenReturn(true);

        BillingAccessService.Decision decision = accessService.decide(1L, "test-paid");

        assertThat(decision.keySource()).isEqualTo("system");
        assertThat(decision.billable()).isTrue();
        // 付费模型不走免费额度，避免白嫖
        verify(quotaService, never()).consumeQuota(1L);
    }

    @Test
    void paidOnlyModelWithoutBalanceThrowsInsufficientBalance() {
        properties.setEnabled(true);
        when(apiKeyService.hasKey(1L)).thenReturn(false);
        when(walletService.canBill(1L)).thenReturn(false);
        when(walletService.balance(1L)).thenReturn(0L);

        assertThatThrownBy(() -> accessService.decide(1L, "test-paid"))
                .isInstanceOf(InsufficientBalanceException.class);
    }

    @Test
    void paidOnlyModelWhileBillingDisabledThrowsServiceUnavailable() {
        properties.setEnabled(false);
        when(apiKeyService.hasKey(1L)).thenReturn(false);

        assertThatThrownBy(() -> accessService.decide(1L, "test-paid"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("充值服务暂未开放");
        // 开关关闭时不应误报余额不足，也不触碰钱包查询

        verify(walletService, never()).canBill(1L);
    }

    @Test
    void unknownModelIsRejectedBeforeAnyResourceConsumption() {
        assertThatThrownBy(() -> accessService.decide(1L, "no-such-model"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的模型");
        verify(quotaService, never()).consumeQuota(1L);
    }

    @Test
    void freeModelFollowsNormalQuotaChain() {
        when(apiKeyService.hasKey(1L)).thenReturn(false);
        when(quotaService.isEnabled()).thenReturn(false);

        BillingAccessService.Decision decision = accessService.decide(1L, "test-free");

        assertThat(decision.keySource()).isEqualTo("system");
        assertThat(decision.billable()).isFalse();
    }
}
