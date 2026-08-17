package com.offerforge.auth;

import com.offerforge.common.ErrorCode;
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
    private final String passwordTimingHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, TokenStore tokenStore,
                       LoginRateLimiter loginRateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenStore = tokenStore;
        this.loginRateLimiter = loginRateLimiter;
        this.passwordTimingHash = passwordEncoder.encode("offerforge-login-timing-only");
    }

    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }

        UserEntity user = new UserEntity();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(generateDefaultNickname());

        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException exception) { // 防止并发注册
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }

        return new RegisterResponse(user.getId(), user.getUsername(), user.getNickname());
    }

    public LoginResponse login(LoginRequest request) {
        loginRateLimiter.checkAllowed(request.username());
        UserEntity user = userRepository.findByUsername(request.username()).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), passwordTimingHash); // 时序补偿，避免用户名枚举
            loginRateLimiter.recordFailure(request.username());
            throw new BusinessException(ErrorCode.AUTH_FAILED);
        }

        if (user.getStatus() != null && user.getStatus() != 0) {
            loginRateLimiter.recordFailure(request.username());
            throw new BusinessException(ErrorCode.FORBIDDEN, "用户已被禁用");
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            loginRateLimiter.recordFailure(request.username());
            throw new BusinessException(ErrorCode.AUTH_FAILED);
        }

        loginRateLimiter.clear(request.username());

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
