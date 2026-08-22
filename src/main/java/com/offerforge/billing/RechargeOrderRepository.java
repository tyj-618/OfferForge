package com.offerforge.billing;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RechargeOrderRepository extends JpaRepository<RechargeOrder, Long> {

    Optional<RechargeOrder> findByOrderNo(String orderNo);

    /** 确认到账专用：行锁防并发重复入账（渠道重复回调时仅首个事务置 PAID 并落钱包） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from RechargeOrder o where o.orderNo = :orderNo")
    Optional<RechargeOrder> lockedByOrderNo(@Param("orderNo") String orderNo);

    /** 本人订单倒序（最新在前） */
    List<RechargeOrder> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    /** 幂等复用：同用户同金额最近的待支付单 */
    Optional<RechargeOrder> findFirstByUserIdAndAmountCentsAndStatusOrderByIdDesc(
            Long userId, long amountCents, String status);
}
