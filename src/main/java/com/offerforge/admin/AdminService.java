package com.offerforge.admin;

import com.offerforge.auth.CurrentUserService;
import com.offerforge.auth.UserEntity;
import com.offerforge.auth.UserRepository;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 管理台核心逻辑：管理员身份认定（配置列表，用户名或邮箱任一命中）、统计概览、用户分页检索、封禁/解封。
 * 封禁即时性：被封禁用户的既有 token 在过期前仍有效，登录/刷新会话时校验 status 拒绝。
 */
@Service
public class AdminService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final AdminProperties properties;

    public AdminService(UserRepository userRepository, CurrentUserService currentUserService,
                        AdminProperties properties) {
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
        this.properties = properties;
    }

    /** 管理员身份认定：用户名或邮箱命中配置列表（忽略大小写，空白项忽略）；邮箱命中兼容验证码自动注册的随机用户名账号 */
    public boolean isAdmin(Long userId) {
        return userRepository.findById(userId)
                .map(this::isAdminIdentity)
                .orElse(false);
    }

    /** 管理员鉴权：未登录 40100，非管理员 40300 */
    public Long requireAdmin(String authorization) {
        Long userId = currentUserService.requireUserId(authorization);
        if (!isAdmin(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return userId;
    }

    public AdminStats stats() {
        Instant startOfToday = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        return new AdminStats(
                userRepository.count(),
                userRepository.countByCreatedAtGreaterThanEqual(startOfToday),
                userRepository.countByStatus(1));
    }

    public AdminUserPage listUsers(String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<UserEntity> result;
        if (keyword == null || keyword.isBlank()) {
            result = userRepository.findAll(pageable);
        } else {
            String trimmed = keyword.trim();
            result = userRepository.findByUsernameContainingIgnoreCaseOrNicknameContainingIgnoreCaseOrEmailContainingIgnoreCase(
                    trimmed, trimmed, trimmed, pageable);
        }
        List<AdminUserItem> items = result.getContent().stream()
                .map(this::toItem)
                .toList();
        return new AdminUserPage(items, safePage, safeSize, result.getTotalElements());
    }

    /** 封禁：目标为管理员时拒绝（防止误操作/越权锁死管理账号） */
    @Transactional
    public void ban(Long targetId) {
        UserEntity user = userRepository.findById(targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        if (isAdminIdentity(user)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能封禁管理员账号");
        }
        user.setStatus(1);
        userRepository.save(user);
    }

    @Transactional
    public void unban(Long targetId) {
        UserEntity user = userRepository.findById(targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        user.setStatus(0);
        userRepository.save(user);
    }

    private AdminUserItem toItem(UserEntity user) {
        return new AdminUserItem(user.getId(), user.getUsername(), user.getNickname(),
                user.getEmail(), user.getStatus(),
                user.getCreatedAt() == null ? "" : TIME_FORMAT.format(user.getCreatedAt()),
                isAdminIdentity(user));
    }

    private boolean isAdminIdentity(UserEntity user) {
        return matchesConfiguredIdentity(user.getUsername()) || matchesConfiguredIdentity(user.getEmail());
    }

    private boolean matchesConfiguredIdentity(String value) {
        List<String> identities = properties.getUsernames();
        if (identities == null || value == null || value.isBlank()) {
            return false;
        }
        return identities.stream()
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .anyMatch(name -> name.equalsIgnoreCase(value));
    }
}
