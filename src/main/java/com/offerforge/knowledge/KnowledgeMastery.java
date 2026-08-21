package com.offerforge.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 资料掌握度标记：每个用户对每道资料问答的绿勾（已掌握）/红叉（待加强）累计。
 * 同一题同一时刻只存在一种标记（勾叉互相抵消），数量 1~10；
 * 绿勾降低出题概率（10 勾永不出题），红叉提高出现频率（10 叉权重最高）。
 */
@Entity
@Table(name = "knowledge_mastery", uniqueConstraints = {
        @UniqueConstraint(name = "uk_mastery_user_item", columnNames = {"user_id", "knowledge_item_id"})
})
public class KnowledgeMastery {

    /** 标记类型：CHECK 绿勾（已掌握）/ CROSS 红叉（待加强） */
    public enum MarkType {
        CHECK, CROSS
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 标记归属用户：官方题全局共享，但掌握度按用户独立累计 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "knowledge_item_id", nullable = false)
    private Long knowledgeItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mark_type", nullable = false, length = 8)
    private MarkType markType;

    /** 标记数量：1~10，抵消归零时记录直接删除 */
    @Column(name = "mark_count", nullable = false)
    private int markCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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

    public Long getKnowledgeItemId() {
        return knowledgeItemId;
    }

    public void setKnowledgeItemId(Long knowledgeItemId) {
        this.knowledgeItemId = knowledgeItemId;
    }

    public MarkType getMarkType() {
        return markType;
    }

    public void setMarkType(MarkType markType) {
        this.markType = markType;
    }

    public int getMarkCount() {
        return markCount;
    }

    public void setMarkCount(int markCount) {
        this.markCount = markCount;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
