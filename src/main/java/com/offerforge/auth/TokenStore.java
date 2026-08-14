package com.offerforge.auth;

import java.util.Optional;

public interface TokenStore {

    TokenSession createSession(Long userId);

    Optional<Long> findUserId(String token);

    void remove(String token);

    Optional<TokenSession> refreshSession(String refreshToken);

    void removeRefreshToken(String refreshToken);

    default Optional<String> resolveBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authorization.substring("Bearer ".length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
