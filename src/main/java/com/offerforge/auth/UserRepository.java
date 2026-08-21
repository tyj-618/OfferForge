package com.offerforge.auth;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    // 管理台：统计与分页检索（用户名/昵称/邮箱模糊匹配）
    long countByStatus(Integer status);

    long countByCreatedAtGreaterThanEqual(Instant createdAt);

    Page<UserEntity> findByUsernameContainingIgnoreCaseOrNicknameContainingIgnoreCaseOrEmailContainingIgnoreCase(
            String username, String nickname, String email, Pageable pageable);
}
