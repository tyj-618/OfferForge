package com.offerforge.auth;

import com.offerforge.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String REFRESH_COOKIE = "offerforge_refresh";

    private final AuthService authService;
    private final AuthProperties authProperties;

    public AuthController(AuthService authService, AuthProperties authProperties) {
        this.authService = authService;
        this.authProperties = authProperties;
    }

    @PostMapping("/register")
    public ApiResponse<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        LoginResponse loginResponse = authService.login(request);
        writeRefreshCookie(response, loginResponse.refreshToken(), loginResponse.refreshExpiresIn());
        return ApiResponse.success(loginResponse);
    }

    @PostMapping("/refresh")
    public ApiResponse<RefreshTokenResponse> refresh(
            @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response
    ) {
        TokenSession session = authService.refresh(refreshToken);
        writeRefreshCookie(response, session.refreshToken(), session.refreshExpiresIn());
        return ApiResponse.success(new RefreshTokenResponse(session.token(), session.expiresIn()));
    }

    @PostMapping("/logout")
    public ApiResponse<Boolean> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response
    ) {
        authService.logout(authorization, refreshToken);
        clearRefreshCookie(response);
        return ApiResponse.success(true);
    }

    private void writeRefreshCookie(HttpServletResponse response, String token, long expiresIn) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                .secure(authProperties.isRefreshCookieSecure())
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(expiresIn)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(authProperties.isRefreshCookieSecure())
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
