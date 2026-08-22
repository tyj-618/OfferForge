package com.offerforge.billing;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    /** 本人流水倒序（最新在前） */
    List<WalletTransaction> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
}
