package com.offerforge.auth;

public record RefreshTokenResponse(String token, long expiresIn) {
}
