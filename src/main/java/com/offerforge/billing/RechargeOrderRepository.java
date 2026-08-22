package com.offerforge.billing;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RechargeOrderRepository extends JpaRepository<RechargeOrder, Long> {

    Optional<RechargeOrder> findByOrderNo(String orderNo);

    /** 本人订单倒序（最新在前） */
    List<RechargeOrder> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    /** 幂等复用：同用户同金额最近的待支付单 */
    Optional<RechargeOrder> findFirstByUserIdAndAmountCentsAndStatusOrderByIdDesc(
            Long userId, long amountCents, String status);
}
