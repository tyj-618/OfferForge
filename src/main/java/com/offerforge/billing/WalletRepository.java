package com.offerforge.billing;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 钱包仓库：余额变动统一走 {@code lockedByUserId} 行锁查询，事务内串行化防并发超扣。
 */
public interface WalletRepository extends JpaRepository<UserWallet, Long> {

    Optional<UserWallet> findByUserId(Long userId);

    /** 行锁版本：充值入账/消费扣减必须在事务内使用此查询 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from UserWallet w where w.userId = :userId")
    Optional<UserWallet> lockedByUserId(@Param("userId") Long userId);
}
