package com.offerforge.auth;

import com.offerforge.common.ErrorCode;
import com.offerforge.email.EmailService;
import com.offerforge.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenStore tokenStore;
    private final LoginRateLimiter loginRateLimiter;
    private final EmailService emailService;
    private final String passwordTimingHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, TokenStore tokenStore,
                       LoginRateLimiter loginRateLimiter, EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenStore = tokenStore;
        this.loginRateLimiter = loginRateLimiter;
        this.emailService = emailService;
        this.passwordTimingHash = passwordEncoder.encode("offerforge-login-timing-only");
    }

    /**
     * 邮箱注册：邮箱 + 验证码 + 用户名 + 密码，账号与邮箱一一对应。
     * 先完成邮箱/用户名唯一性校验再消耗验证码，避免非法请求白白作废用户收到的码。
     */
    public RegisterResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_EXISTS);
        }
        if (userRepository.existsByUsername(request.username())) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }
        if (!emailService.verifyCode(email, request.code())) {
            throw new BusinessException(ErrorCode.EMAIL_CODE_INVALID);
        }

        UserEntity user = new UserEntity();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(generateDefaultNickname());
        user.setEmail(email);

        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException exception) { // 并发注册撞用户名/邮箱
            throw userRepository.existsByEmail(email)
                    ? new BusinessException(ErrorCode.EMAIL_EXISTS)
                    : new BusinessException(ErrorCode.USERNAME_EXISTS);
        }

        return new RegisterResponse(user.getId(), user.getUsername(), user.getNickname());
    }

    /**
     * 密码登录：账号字段兼容用户名或邮箱（先按用户名匹配，未命中再按邮箱匹配）。
     */
    public LoginResponse login(LoginRequest request) {
        String identifier = request.username().trim();
        loginRateLimiter.checkAllowed(identifier);
        UserEntity user = userRepository.findByUsername(identifier)
                .or(() -> userRepository.findByEmail(identifier.toLowerCase()))
                .orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), passwordTimingHash); // 时序补偿，避免账号枚举
            loginRateLimiter.recordFailure(identifier);
            throw new BusinessException(ErrorCode.AUTH_FAILED);
        }

        if (user.getStatus() != null && user.getStatus() != 0) {
            loginRateLimiter.recordFailure(identifier);
            throw new BusinessException(ErrorCode.FORBIDDEN, "用户已被禁用");
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            loginRateLimiter.recordFailure(identifier);
            throw new BusinessException(ErrorCode.AUTH_FAILED);
        }

        loginRateLimiter.clear(identifier);

        TokenSession session = tokenStore.createSession(user.getId());
        UserSummary userSummary = new UserSummary(user.getId(), user.getUsername(), user.getNickname());

        return new LoginResponse(
                session.token(),
                session.expiresIn(),
                session.refreshToken(),
                session.refreshExpiresIn(),
                userSummary
        );
    }

    /**
     * 忘记密码：邮箱 + 验证码通过后重置密码。
     * 先校验邮箱已注册再消耗验证码，避免陌生邮箱白白作废用户的码；
     * 错误信息不区分「邮箱未注册/验证码错误」之外的细节由错误码承载，枚举风险由验证码防刷锁定兜底。
     */
    public void resetPassword(ResetPasswordRequest request) {
        String email = request.email().trim().toLowerCase();
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "该邮箱未注册账号"));
        if (!emailService.verifyCode(email, request.code())) {
            throw new BusinessException(ErrorCode.EMAIL_CODE_INVALID);
        }
        if (user.getStatus() != null && user.getStatus() != 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "用户已被禁用");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    public void logout(String authorization, String refreshToken) {
        String token = tokenStore.resolveBearerToken(authorization).orElse(null);
        if (token != null) {
            tokenStore.remove(token);
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            tokenStore.removeRefreshToken(refreshToken);
        }
        if (token == null && (refreshToken == null || refreshToken.isBlank())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    public TokenSession refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已失效");
        }
        return tokenStore.refreshSession(refreshToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已失效"));
    }

    /**
     * 按 userId 查当前登录用户摘要（顶栏展示用户名）；不存在时 NOT_FOUND。
     */
    public UserSummary userSummary(Long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        return new UserSummary(user.getId(), user.getUsername(), user.getNickname());
    }

    private String generateDefaultNickname() {
        return "Candidate_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
