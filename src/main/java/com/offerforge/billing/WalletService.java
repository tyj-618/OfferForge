package com.offerforge.billing;

import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 钱包服务：余额查询/充值入账/消费扣减/流水。
 * 余额变动一律事务内行锁串行化，扣减保底 0 不超扣；每笔变动落流水保证账实可审计。
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;
    private final BillingProperties properties;

    public WalletService(WalletRepository walletRepository,
                         WalletTransactionRepository transactionRepository,
                         BillingProperties properties) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.properties = properties;
    }

    /** 当前余额（分）；无钱包记录视为 0 */
    public long balance(Long userId) {
        return walletRepository.findByUserId(userId).map(UserWallet::getBalanceCents).orElse(0L);
    }

    /** 是否可进入计费模式：付费总开关开启且余额大于 0 */
    public boolean canBill(Long userId) {
        return properties.isEnabled() && balance(userId) > 0;
    }

    /** 付费模型选用资格：余额大于 0（与总开关共同由调用方把关） */
    public boolean hasBalance(Long userId) {
        return balance(userId) > 0;
    }

    /**
     * 充值入账：行锁内增加余额并累计充值总额，落充值流水。
     * 并发首次充值靠唯一索引兜底重试，不会重复建钱包。
     */
    @Transactional
    public long recharge(Long userId, long amountCents, String refNo) {
        if (amountCents <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "充值金额非法");
        }
        UserWallet wallet = lockedOrCreate(userId);
        wallet.setBalanceCents(wallet.getBalanceCents() + amountCents);
        wallet.setTotalRechargedCents(wallet.getTotalRechargedCents() + amountCents);
        walletRepository.save(wallet);
        appendTransaction(userId, WalletTransaction.TYPE_RECHARGE, amountCents,
                wallet.getBalanceCents(), refNo, null);
        log.info("wallet recharged userId={} amountCents={} balanceCents={} refNo={}",
                userId, amountCents, wallet.getBalanceCents(), refNo);
        return wallet.getBalanceCents();
    }

    /**
     * 消费扣减：行锁内按余额保底扣减（不足时扣到 0），落消费流水，返回实际扣减金额。
     * 实际扣减为 0 时不落流水；扣到 0 后由开局/回合预检阻断后续服务。
     */
    @Transactional
    public long consume(Long userId, long amountCents, String refNo, String detail) {
        if (amountCents <= 0) {
            return 0;
        }
        UserWallet wallet = walletRepository.lockedByUserId(userId).orElse(null);
        if (wallet == null || wallet.getBalanceCents() <= 0) {
            return 0;
        }
        long deducted = Math.min(amountCents, wallet.getBalanceCents());
        wallet.setBalanceCents(wallet.getBalanceCents() - deducted);
        walletRepository.save(wallet);
        appendTransaction(userId, WalletTransaction.TYPE_CONSUME, deducted,
                wallet.getBalanceCents(), refNo, detail);
        log.info("wallet consumed userId={} deductedCents={} balanceCents={} detail={}",
                userId, deducted, wallet.getBalanceCents(), detail);
        return deducted;
    }

    /** 流水倒序（最新在前） */
    public List<WalletTransaction> transactions(Long userId, int limit) {
        return transactionRepository.findByUserIdOrderByIdDesc(userId, PageRequest.of(0, limit));
    }

    private UserWallet lockedOrCreate(Long userId) {
        return walletRepository.lockedByUserId(userId).orElseGet(() -> {
            UserWallet wallet = new UserWallet();
            wallet.setUserId(userId);
            try {
                return walletRepository.saveAndFlush(wallet);
            } catch (DataIntegrityViolationException exception) {
                // 并发首次充值：唯一索引冲突后重取行锁
                return walletRepository.lockedByUserId(userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "钱包初始化失败，请重试"));
            }
        });
    }

    private void appendTransaction(Long userId, String type, long amountCents,
                                   long balanceAfterCents, String refNo, String detail) {
        WalletTransaction transaction = new WalletTransaction();
        transaction.setUserId(userId);
        transaction.setType(type);
        transaction.setAmountCents(amountCents);
        transaction.setBalanceAfterCents(balanceAfterCents);
        transaction.setRefNo(refNo);
        transaction.setDetail(detail);
        transactionRepository.save(transaction);
    }
}
