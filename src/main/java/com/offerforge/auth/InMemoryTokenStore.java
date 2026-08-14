package com.offerforge.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!redis")
public class InMemoryTokenStore implements TokenStore {

    private final AuthProperties authProperties;
    private final Map<String, SessionEntry> accessTokens = new ConcurrentHashMap<>();
    private final Map<String, SessionEntry> refreshTokens = new ConcurrentHashMap<>();

    public InMemoryTokenStore(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public TokenSession createSession(Long userId) {
        long now = System.currentTimeMillis();
        long accessExpiresAt = now + authProperties.getAccessTokenTtlSeconds() * 1000;
        long refreshExpiresAt = now + authProperties.getRefreshTokenTtlSeconds() * 1000;
        String token = newToken();
        String refreshToken = newToken();
        accessTokens.put(token, new SessionEntry(userId, accessExpiresAt));
        refreshTokens.put(refreshToken, new SessionEntry(userId, refreshExpiresAt));
        return new TokenSession(
                token,
                authProperties.getAccessTokenTtlSeconds(),
                refreshToken,
                authProperties.getRefreshTokenTtlSeconds()
        );
    }

    @Override
    public Optional<Long> findUserId(String token) {
        SessionEntry entry = accessTokens.get(token);
        if (entry == null || entry.expiresAt() < System.currentTimeMillis()) {
            return Optional.empty();
        }
        return Optional.of(entry.userId());
    }

    @Override
    public void remove(String token) {
        accessTokens.remove(token);
    }

    @Override
    public Optional<TokenSession> refreshSession(String refreshToken) {
        SessionEntry entry = refreshTokens.remove(refreshToken);
        if (entry == null || entry.expiresAt() < System.currentTimeMillis()) {
            return Optional.empty();
        }
        return Optional.of(createSession(entry.userId()));
    }

    @Override
    public void removeRefreshToken(String refreshToken) {
        refreshTokens.remove(refreshToken);
    }

    private String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record SessionEntry(Long userId, long expiresAt) {
    }
}
