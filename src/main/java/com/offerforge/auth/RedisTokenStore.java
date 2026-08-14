package com.offerforge.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Component
@Profile("redis")
public class RedisTokenStore implements TokenStore {

    private static final String ACCESS_KEY_PREFIX = "offerforge:auth:token:";
    private static final String REFRESH_KEY_PREFIX = "offerforge:auth:refresh:";

    private final StringRedisTemplate redisTemplate;
    private final AuthProperties authProperties;

    public RedisTokenStore(StringRedisTemplate redisTemplate, AuthProperties authProperties) {
        this.redisTemplate = redisTemplate;
        this.authProperties = authProperties;
    }

    @Override
    public TokenSession createSession(Long userId) {
        String token = newToken();
        String refreshToken = newToken();
        redisTemplate.opsForValue().set(
                ACCESS_KEY_PREFIX + token,
                String.valueOf(userId),
                Duration.ofSeconds(authProperties.getAccessTokenTtlSeconds()));
        redisTemplate.opsForValue().set(
                REFRESH_KEY_PREFIX + refreshToken,
                String.valueOf(userId),
                Duration.ofSeconds(authProperties.getRefreshTokenTtlSeconds()));
        return new TokenSession(
                token,
                authProperties.getAccessTokenTtlSeconds(),
                refreshToken,
                authProperties.getRefreshTokenTtlSeconds()
        );
    }

    @Override
    public Optional<Long> findUserId(String token) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(ACCESS_KEY_PREFIX + token))
                .map(Long::parseLong);
    }

    @Override
    public void remove(String token) {
        redisTemplate.delete(ACCESS_KEY_PREFIX + token);
    }

    @Override
    public Optional<TokenSession> refreshSession(String refreshToken) {
        String key = REFRESH_KEY_PREFIX + refreshToken;
        // GETDEL 原子消费 refresh token，避免并发刷新时同一 token 被重复使用
        String userId = redisTemplate.opsForValue().getAndDelete(key);
        if (userId == null) {
            return Optional.empty();
        }
        return Optional.of(createSession(Long.parseLong(userId)));
    }

    @Override
    public void removeRefreshToken(String refreshToken) {
        redisTemplate.delete(REFRESH_KEY_PREFIX + refreshToken);
    }

    private String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
