package com.offerforge.ratelimit;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存滑动窗口限流器：每个 key 维护一个时间戳队列，剔除窗口外的旧记录后判断是否超限。
 * 单实例部署（默认无 Redis）下生效；惰性清理长期不活跃的 key 防止无界增长。
 */
@Component
@Profile("!redis")
public class InMemoryRateLimiter implements RateLimiter {

    /** 惰性清理频率：每 N 次调用扫一次过期 key */
    private static final long CLEANUP_INTERVAL = 256;
    /** key 不活跃超过该时长（窗口最大值）后回收 */
    private static final long IDLE_EVICT_MILLIS = 5 * 60 * 1000L;

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final AtomicLong calls = new AtomicLong();

    @Override
    public boolean tryAcquire(String key, int limit, long windowMillis) {
        if (calls.incrementAndGet() % CLEANUP_INTERVAL == 0) {
            evictIdle(windowMillis);
        }
        long now = System.currentTimeMillis();
        Deque<Long> queue = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && queue.peekFirst() <= now - windowMillis) {
                queue.pollFirst();
            }
            if (queue.size() >= limit) {
                return false;
            }
            queue.addLast(now);
            return true;
        }
    }

    /** 回收窗口外且长期无请求的 key（客户端放弃后不会主动清理） */
    private void evictIdle(long windowMillis) {
        long now = System.currentTimeMillis();
        long idleThreshold = Math.max(windowMillis, IDLE_EVICT_MILLIS);
        windows.forEach((key, queue) -> {
            synchronized (queue) {
                Long last = queue.peekLast();
                if (last != null && now - last > idleThreshold) {
                    windows.remove(key, queue);
                }
            }
        });
    }
}
