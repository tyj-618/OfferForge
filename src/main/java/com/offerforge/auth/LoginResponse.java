package com.offerforge.auth;

public record LoginResponse(
        String token,
        long expiresIn,
        String refreshToken,
        long refreshExpiresIn,
        UserSummary user
) {
}
