package com.offerforge.quota;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存额度计数器：单实例部署（默认无 Redis）与测试场景下生效。
 * 日期编码在 key 中实现懒重置；惰性清理过期日期条目防止无界增长。
 */
@Component
@Profile("!redis")
public class InMemoryQuotaStore implements QuotaStore {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    /** 最近一次使用的日期，日期翻转时整体清空旧条目 */
    private volatile String latestDay = "";

    @Override
    public long consume(Long userId, String day) {
        resetIfNewDay(day);
        return counters.computeIfAbsent(key(userId, day), ignored -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public long used(Long userId, String day) {
        AtomicLong counter = counters.get(key(userId, day));
        return counter == null ? 0 : counter.get();
    }

    private void resetIfNewDay(String day) {
        if (!day.equals(latestDay)) {
            synchronized (this) {
                if (!day.equals(latestDay)) {
                    counters.clear();
                    latestDay = day;
                }
            }
        }
    }

    private String key(Long userId, String day) {
        return userId + ":" + day;
    }
}
