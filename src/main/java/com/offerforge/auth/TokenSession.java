package com.offerforge.auth;

public record TokenSession(String token, long expiresIn, String refreshToken, long refreshExpiresIn) {
}
