package com.offerforge.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 钱包流水：每笔充值/消费/退款一条，记录变动后余额快照，保证账实可审计。
 * amountCents 恒为正，方向由 type 表达（RECHARGE 入账 / CONSUME 消费 / REFUND 退款）。
 */
@Entity
@Table(name = "wallet_transaction")
public class WalletTransaction {

    public static final String TYPE_RECHARGE = "RECHARGE";
    public static final String TYPE_CONSUME = "CONSUME";
    public static final String TYPE_REFUND = "REFUND";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** RECHARGE / CONSUME / REFUND */
    @Column(nullable = false, length = 16)
    private String type;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    /** 变动后的余额快照（分） */
    @Column(name = "balance_after_cents", nullable = false)
    private long balanceAfterCents;

    /** 关联单号：充值订单号或会话 id（可空） */
    @Column(name = "ref_no", length = 64)
    private String refNo;

    /** 备注：消费流水记录计费模型名等 */
    @Column(length = 128)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public void setAmountCents(long amountCents) {
        this.amountCents = amountCents;
    }

    public long getBalanceAfterCents() {
        return balanceAfterCents;
    }

    public void setBalanceAfterCents(long balanceAfterCents) {
        this.balanceAfterCents = balanceAfterCents;
    }

    public String getRefNo() {
        return refNo;
    }

    public void setRefNo(String refNo) {
        this.refNo = refNo;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
