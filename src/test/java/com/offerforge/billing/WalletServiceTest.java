package com.offerforge.billing;

import com.offerforge.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 钱包服务：充值入账累计、消费扣减保底 0 不超扣、流水快照正确；开关关闭不可计费。
 */
class WalletServiceTest {

    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final WalletTransactionRepository transactionRepository = mock(WalletTransactionRepository.class);
    private final BillingProperties properties = new BillingProperties();

    private final WalletService walletService = new WalletService(walletRepository, transactionRepository, properties);

    private UserWallet wallet(long balanceCents) {
        UserWallet wallet = new UserWallet();
        wallet.setUserId(1L);
        wallet.setBalanceCents(balanceCents);
        return wallet;
    }

    @Test
    void balanceDefaultsToZeroWithoutWallet() {
        when(walletRepository.findByUserId(1L)).thenReturn(Optional.empty());

        assertThat(walletService.balance(1L)).isZero();
    }

    @Test
    void canBillRequiresEnabledFlagAndPositiveBalance() {
        when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(wallet(100)));

        properties.setEnabled(false);
        assertThat(walletService.canBill(1L)).isFalse();
        properties.setEnabled(true);
        assertThat(walletService.canBill(1L)).isTrue();

        when(walletRepository.findByUserId(1L)).thenReturn(Optional.of(wallet(0)));
        assertThat(walletService.canBill(1L)).isFalse();
    }

    @Test
    void rechargeCreatesWalletAndAccumulatesBalanceAndTotal() {
        when(walletRepository.lockedByUserId(1L)).thenReturn(Optional.empty());
        when(walletRepository.saveAndFlush(any(UserWallet.class))).thenAnswer(invocation -> {
            UserWallet saved = invocation.getArgument(0);
            saved.setId(9L);
            return saved;
        });
        when(walletRepository.save(any(UserWallet.class))).thenAnswer(invocation -> invocation.getArgument(0));

        long balanceAfter = walletService.recharge(1L, 1000, "OF123");

        assertThat(balanceAfter).isEqualTo(1000);
        // lockedOrCreate 内部自建钱包：断言实际落库对象而非外部构造对象
        ArgumentCaptor<UserWallet> walletCaptor = ArgumentCaptor.forClass(UserWallet.class);
        verify(walletRepository).saveAndFlush(walletCaptor.capture());
        UserWallet created = walletCaptor.getValue();
        assertThat(created.getUserId()).isEqualTo(1L);
        assertThat(created.getBalanceCents()).isEqualTo(1000);
        assertThat(created.getTotalRechargedCents()).isEqualTo(1000);

        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionRepository).save(captor.capture());
        WalletTransaction transaction = captor.getValue();
        assertThat(transaction.getType()).isEqualTo(WalletTransaction.TYPE_RECHARGE);
        assertThat(transaction.getAmountCents()).isEqualTo(1000);
        assertThat(transaction.getBalanceAfterCents()).isEqualTo(1000);
        assertThat(transaction.getRefNo()).isEqualTo("OF123");
    }

    @Test
    void rechargeRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> walletService.recharge(1L, 0, null))
                .isInstanceOf(BusinessException.class);
        verify(walletRepository, never()).save(any());
    }

    @Test
    void consumeCapsAtBalanceAndRecordsSnapshot() {
        UserWallet existing = wallet(50);
        when(walletRepository.lockedByUserId(1L)).thenReturn(Optional.of(existing));
        when(walletRepository.save(any(UserWallet.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 余额 50 扣 80：保底扣到 0，返回实扣 50，不超扣为负
        long deducted = walletService.consume(1L, 80, "session-1", "model:test");

        assertThat(deducted).isEqualTo(50);
        assertThat(existing.getBalanceCents()).isZero();

        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionRepository).save(captor.capture());
        WalletTransaction transaction = captor.getValue();
        assertThat(transaction.getType()).isEqualTo(WalletTransaction.TYPE_CONSUME);
        assertThat(transaction.getAmountCents()).isEqualTo(50);
        assertThat(transaction.getBalanceAfterCents()).isZero();
        assertThat(transaction.getDetail()).isEqualTo("model:test");
    }

    @Test
    void consumeWithEmptyBalanceIsNoOp() {
        when(walletRepository.lockedByUserId(1L)).thenReturn(Optional.of(wallet(0)));

        assertThat(walletService.consume(1L, 100, null, null)).isZero();
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void consumeWithoutWalletIsNoOp() {
        when(walletRepository.lockedByUserId(1L)).thenReturn(Optional.empty());

        assertThat(walletService.consume(1L, 100, null, null)).isZero();
        verify(transactionRepository, never()).save(any());
    }
}
