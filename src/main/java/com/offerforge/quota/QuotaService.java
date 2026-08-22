package com.offerforge.quota;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 免费额度服务：按用户维度每日限额（完整面试次数）。
 * 懒重置：日期编码在计数器 key 中，check/consume 时自动使用当日计数器。
 * enabled=false 时视为不限量（本地开发/测试）。
 */
@Service
public class QuotaService {

    private final QuotaStore store;
    private final QuotaProperties properties;
    private final Clock clock;

    public QuotaService(QuotaStore store, QuotaProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public int dailyLimit() {
        return properties.getDailyLimit();
    }

    /**
     * 有效场次计次门槛：问答次数不足该值的场次视为无效场次，
     * 结束时不消耗免费额度（退还开局扣减）且不记录历史。
     */
    public int minBillableQuestions() {
        return properties.getMinBillableQuestions();
    }

    /**
     * 剩余次数；额度关闭时返回 dailyLimit（展示上限，不约束）。
     */
    public int checkQuota(Long userId) {
        if (!properties.isEnabled()) {
            return properties.getDailyLimit();
        }
        long used = store.used(userId, today());
        return (int) Math.max(0, properties.getDailyLimit() - used);
    }

    /**
     * 扣减一次：原子自增后判定是否在上限内；超限返回 false（计数保留，不回退）。
     */
    public boolean consumeQuota(Long userId) {
        if (!properties.isEnabled()) {
            return true;
        }
        return store.consume(userId, today()) <= properties.getDailyLimit();
    }

    /**
     * 退还一次已扣次数：短场面试（问答次数不足计次门槛）结束时回退开局扣减；额度关闭时无操作。
     */
    public void refundQuota(Long userId) {
        if (!properties.isEnabled()) {
            return;
        }
        store.refund(userId, today());
    }

    private String today() {
        return LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
