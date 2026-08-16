package com.offerforge.quota;

/**
 * 免费额度计数器存储：按 userId + 日期（yyyyMMdd）维度记录已用次数。
 * 懒重置：日期编码在 key 中，新的一天天然使用新计数器，无需清理任务。
 */
public interface QuotaStore {

    /**
     * 原子自增并返回当日已用次数（含本次）。
     */
    long consume(Long userId, String day);

    /**
     * 当日已用次数（不含自增）；无记录返回 0。
     */
    long used(Long userId, String day);
}
