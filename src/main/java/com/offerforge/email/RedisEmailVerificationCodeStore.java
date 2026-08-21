package com.offerforge.email;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 生产实现：验证码与防刷/锁定状态存 Redis。
 * key 设计：email:code:{email}（TTL 5min）/ email:send_time:{email}（TTL 60s）/ email:lock:{email}（TTL 15min）。
 */
@Component
@Profile("redis")
public class RedisEmailVerificationCodeStore implements EmailVerificationCodeStore {

    private static final String CODE_KEY_PREFIX = "email:code:";
    private static final String SEND_TIME_KEY_PREFIX = "email:send_time:";
    private static final String LOCK_KEY_PREFIX = "email:lock:";

    static final int MAX_FAILURES = 5;

    private final StringRedisTemplate redisTemplate;

    public RedisEmailVerificationCodeStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void saveCode(String email, String code) {
        redisTemplate.opsForValue().set(CODE_KEY_PREFIX + email, code, Duration.ofMinutes(5));
    }

    @Override
    public String getCode(String email) {
        return redisTemplate.opsForValue().get(CODE_KEY_PREFIX + email);
    }

    @Override
    public void removeCode(String email) {
        redisTemplate.delete(CODE_KEY_PREFIX + email);
    }

    @Override
    public boolean hasRecentSendMark(String email) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(SEND_TIME_KEY_PREFIX + email));
    }

    @Override
    public void markSent(String email) {
        redisTemplate.opsForValue().set(SEND_TIME_KEY_PREFIX + email, String.valueOf(System.currentTimeMillis()),
                Duration.ofSeconds(60));
    }

    @Override
    public boolean recordFailure(String email) {
        String key = LOCK_KEY_PREFIX + email;
        Long count = redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, Duration.ofMinutes(15));
        return count != null && count > MAX_FAILURES;
    }

    @Override
    public boolean isLocked(String email) {
        String count = redisTemplate.opsForValue().get(LOCK_KEY_PREFIX + email);
        return count != null && Long.parseLong(count) > MAX_FAILURES;
    }

    @Override
    public void clearFailures(String email) {
        redisTemplate.delete(LOCK_KEY_PREFIX + email);
    }
}
