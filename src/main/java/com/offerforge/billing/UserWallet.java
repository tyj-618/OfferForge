package com.offerforge.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 用户钱包：余额以分为单位，一用户一条（user_id 唯一）。
 * 扣减/入账一律在事务内走行锁（见 WalletRepository），保证并发不超扣。
 */
@Entity
@Table(name = "user_wallet", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_wallet_user_id", columnNames = "user_id")
})
public class UserWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 可用余额（分），保底 0 不为负 */
    @Column(name = "balance_cents", nullable = false)
    private long balanceCents;

    /** 累计充值（分）：运营统计用，不随消费减少 */
    @Column(name = "total_recharged_cents", nullable = false)
    private long totalRechargedCents;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public long getBalanceCents() {
        return balanceCents;
    }

    public void setBalanceCents(long balanceCents) {
        this.balanceCents = balanceCents;
    }

    public long getTotalRechargedCents() {
        return totalRechargedCents;
    }

    public void setTotalRechargedCents(long totalRechargedCents) {
        this.totalRechargedCents = totalRechargedCents;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
