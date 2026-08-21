package com.offerforge.admin;

import com.offerforge.auth.CurrentUserService;
import com.offerforge.auth.UserEntity;
import com.offerforge.auth.UserRepository;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminService 单元测试：身份认定（用户名/邮箱命中、大小写不敏感/空白项忽略）、封禁保护（管理员不可被封）、
 * 封禁/解封状态迁移、分页检索分支（空关键字/关键字）。
 */
class AdminServiceTests {

    private UserRepository userRepository;
    private CurrentUserService currentUserService;
    private AdminProperties properties;
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        currentUserService = mock(CurrentUserService.class);
        properties = new AdminProperties();
        properties.setUsernames(List.of("boss_admin", "  ", "SecondAdmin"));
        adminService = new AdminService(userRepository, currentUserService, properties);
    }

    @Test
    void isAdminMatchesConfiguredUsernamesCaseInsensitively() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "BOSS_ADMIN")));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "secondadmin")));
        when(userRepository.findById(3L)).thenReturn(Optional.of(user(3L, "someone_else")));

        assertThat(adminService.isAdmin(1L)).isTrue();
        assertThat(adminService.isAdmin(2L)).isTrue();
        assertThat(adminService.isAdmin(3L)).isFalse();
    }

    @Test
    void isAdminMatchesConfiguredEmailForAutoRegisteredAccounts() {
        // 邮箱验证码自动注册账号用户名为随机 u_xxx，配置邮箱即可认定为管理员
        UserEntity emailUser = user(4L, "u_abcdef123456");
        emailUser.setEmail("Boss@Example.com");
        properties.setUsernames(List.of("boss@example.com"));
        when(userRepository.findById(4L)).thenReturn(Optional.of(emailUser));

        assertThat(adminService.isAdmin(4L)).isTrue();

        // 邮箱未配置且用户名随机 → 非管理员；无邮箱也不误判
        UserEntity plainUser = user(5L, "u_999");
        plainUser.setEmail("other@example.com");
        when(userRepository.findById(5L)).thenReturn(Optional.of(plainUser));
        assertThat(adminService.isAdmin(5L)).isFalse();
    }

    @Test
    void isAdminFalseForUnknownUserOrEmptyConfig() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());
        assertThat(adminService.isAdmin(9L)).isFalse();

        properties.setUsernames(List.of());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "boss_admin")));
        assertThat(adminService.isAdmin(1L)).isFalse();
    }

    @Test
    void requireAdminRejectsNonAdminWithForbidden() {
        when(currentUserService.requireUserId("Bearer x")).thenReturn(3L);
        when(userRepository.findById(3L)).thenReturn(Optional.of(user(3L, "someone_else")));

        assertThatThrownBy(() -> adminService.requireAdmin("Bearer x"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void requireAdminReturnsUserIdForAdmin() {
        when(currentUserService.requireUserId("Bearer x")).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "boss_admin")));

        assertThat(adminService.requireAdmin("Bearer x")).isEqualTo(1L);
    }

    @Test
    void banSetsStatusToOneAndSaves() {
        UserEntity target = user(5L, "normal_user");
        when(userRepository.findById(5L)).thenReturn(Optional.of(target));

        adminService.ban(5L);

        assertThat(target.getStatus()).isEqualTo(1);
        verify(userRepository).save(target);
    }

    @Test
    void banRejectsAdminTarget() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "boss_admin")));

        assertThatThrownBy(() -> adminService.ban(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("管理员");
        verify(userRepository, never()).save(any());
    }

    @Test
    void banRejectsAdminMatchedByEmail() {
        UserEntity emailAdmin = user(6L, "u_random12345");
        emailAdmin.setEmail("boss@example.com");
        properties.setUsernames(List.of("boss@example.com"));
        when(userRepository.findById(6L)).thenReturn(Optional.of(emailAdmin));

        assertThatThrownBy(() -> adminService.ban(6L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("管理员");
        verify(userRepository, never()).save(any());
    }

    @Test
    void banMissingUserThrowsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminService.ban(99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void unbanResetsStatusToZero() {
        UserEntity target = user(5L, "normal_user");
        target.setStatus(1);
        when(userRepository.findById(5L)).thenReturn(Optional.of(target));

        adminService.unban(5L);

        assertThat(target.getStatus()).isZero();
        verify(userRepository).save(target);
    }

    @Test
    void listUsersBlankKeywordUsesFindAll() {
        when(userRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user(1L, "boss_admin"))));

        AdminUserPage page = adminService.listUsers("  ", 1, 10);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).admin()).isTrue();
        verify(userRepository, never()).findByUsernameContainingIgnoreCaseOrNicknameContainingIgnoreCaseOrEmailContainingIgnoreCase(
                anyString(), anyString(), anyString(), any(Pageable.class));
    }

    @Test
    void listUsersWithKeywordUsesSearchQuery() {
        when(userRepository.findByUsernameContainingIgnoreCaseOrNicknameContainingIgnoreCaseOrEmailContainingIgnoreCase(
                eq("alice"), eq("alice"), eq("alice"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user(7L, "alice_wong"))));

        AdminUserPage page = adminService.listUsers(" alice ", 1, 10);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).username()).isEqualTo("alice_wong");
        verify(userRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void listUsersClampsPageAndSizeIntoSafeBounds() {
        when(userRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        AdminUserPage page = adminService.listUsers(null, -3, 9999);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(50);
    }

    @Test
    void statsAggregatesCounters() {
        when(userRepository.count()).thenReturn(42L);
        when(userRepository.countByCreatedAtGreaterThanEqual(any(Instant.class))).thenReturn(3L);
        when(userRepository.countByStatus(1)).thenReturn(2L);

        AdminStats stats = adminService.stats();

        assertThat(stats).isEqualTo(new AdminStats(42L, 3L, 2L));
    }

    private UserEntity user(Long id, String username) {
        UserEntity entity = new UserEntity();
        entity.setId(id);
        entity.setUsername(username);
        entity.setNickname(username);
        return entity;
    }
}
