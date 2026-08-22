package com.offerforge.auth;

import com.offerforge.common.ApiResponse;
import com.offerforge.email.EmailService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final CurrentUserService currentUserService;
    private final EmailService emailService;

    public AuthController(AuthService authService, AuthProperties authProperties,
                          CurrentUserService currentUserService, EmailService emailService) {
        this.authService = authService;
        this.authProperties = authProperties;
        this.currentUserService = currentUserService;
        this.emailService = emailService;
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

    /**
     * 发送邮箱验证码：格式校验 → 防刷检查（60 秒）→ 腾讯云 SES 发信；
     * 注册与忘记密码共用本端点。错误超限锁定的邮箱同样拒绝发信，避免被当作邮件轰炸工具。
     */
    @PostMapping("/send-code")
    public ApiResponse<Boolean> sendCode(@Valid @RequestBody SendCodeRequest request) {
        emailService.sendVerificationCode(request.email());
        return ApiResponse.success(true);
    }

    /**
     * 忘记密码：邮箱 + 验证码校验通过后重置密码（新密码需 6-64 位）。
     */
    @PostMapping("/reset-password")
    public ApiResponse<Boolean> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.success(true);
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

    /**
     * 当前登录用户摘要：供前端顶栏展示用户名（刷新恢复时 token 在而登录响应丢失的场景）。
     */
    @GetMapping("/me")
    public ApiResponse<UserSummary> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(authService.userSummary(userId));
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
