package com.offerforge.ratelimit;

/**
 * 滑动窗口限流器：key 维度（通常为 userId + 路由）在窗口内最多放行 limit 次。
 * 内存/Redis 双实现，与会话存储一致按 redis profile 切换。
 */
public interface RateLimiter {

    /**
     * 尝试获取一次配额。
     *
     * @param key          限流 key
     * @param limit        窗口内最大请求数
     * @param windowMillis 滑动窗口长度（毫秒）
     * @return true 放行，false 已超限
     */
    boolean tryAcquire(String key, int limit, long windowMillis);
}
