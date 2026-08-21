package com.offerforge.quota;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 额度计数器：key = quota:{userId}:{yyyyMMdd}，value = 已用次数。
 * INCR 原子自增保证并发扣减不超卖；首次写入设置 48h TTL 自动过期（跨天保留便于排查）。
 */
@Component
@Profile("redis")
public class RedisQuotaStore implements QuotaStore {

    private static final String KEY_PREFIX = "quota:";
    private static final Duration KEY_TTL = Duration.ofHours(48);

    private final StringRedisTemplate redisTemplate;

    public RedisQuotaStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public long consume(Long userId, String day) {
        String key = key(userId, day);
        Long used = redisTemplate.opsForValue().increment(key);
        long value = used == null ? 1 : used;
        if (value == 1) {
            redisTemplate.expire(key, KEY_TTL);
        }
        return value;
    }

    @Override
    public long used(Long userId, String day) {
        String value = redisTemplate.opsForValue().get(key(userId, day));
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    @Override
    public void refund(Long userId, String day) {
        String key = key(userId, day);
        Long used = redisTemplate.opsForValue().decrement(key);
        // 无计数器或已扣为 0 时 DECR 会得负值，保底归零避免污染后续扣减判定
        if (used != null && used < 0) {
            redisTemplate.opsForValue().set(key, "0", KEY_TTL);
        }
    }

    private String key(Long userId, String day) {
        return KEY_PREFIX + userId + ":" + day;
    }
}
