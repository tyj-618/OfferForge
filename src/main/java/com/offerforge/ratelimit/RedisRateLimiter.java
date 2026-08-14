package com.offerforge.ratelimit;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 滑动窗口限流器：ZSET 存请求时间戳，Lua 脚本原子完成
 * 剔除窗口外记录 → 计数 → 放行写入 → 设置过期，多实例共享配额。
 */
@Component
@Profile("redis")
public class RedisRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "offerforge:ratelimit:";

    /** ARGV: [窗口起点, 限额, 当前时间戳(成员与分值), TTL 毫秒]；返回 1 放行 / 0 超限 */
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT;

    static {
        SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>();
        SLIDING_WINDOW_SCRIPT.setScriptText("""
                redis.call('ZREMRANGEBYSCORE', KEYS[1], '0', ARGV[1])
                local count = redis.call('ZCARD', KEYS[1])
                if count < tonumber(ARGV[2]) then
                    redis.call('ZADD', KEYS[1], ARGV[3], ARGV[3] .. '-' .. math.random(1000000))
                    redis.call('PEXPIRE', KEYS[1], ARGV[4])
                    return 1
                end
                return 0
                """);
        SLIDING_WINDOW_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryAcquire(String key, int limit, long windowMillis) {
        long now = System.currentTimeMillis();
        Long allowed = redisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                List.of(KEY_PREFIX + key),
                String.valueOf(now - windowMillis),
                String.valueOf(limit),
                String.valueOf(now),
                String.valueOf(windowMillis * 2));
        return allowed != null && allowed == 1L;
    }
}
