package com.offerforge.admin;

import com.offerforge.auth.UserEntity;
import com.offerforge.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 唯一管理账号初始化（生产开关 OFFERFORGE_ADMIN_BOOTSTRAP_ENABLED=true 时装配）：
 * 启动时确保存在用户名 admin、绑定邮箱 3520097134@qq.com、密码 123456 的管理账号；
 * 已存在时校准邮箱绑定与密码（管理员认定仍由 offerforge.admin.usernames 配置）。
 */
@Component
@ConditionalOnProperty(name = "offerforge.admin.bootstrap.enabled", havingValue = "true")
public class AdminAccountInitializer implements ApplicationRunner {

    static final String ADMIN_USERNAME = "admin";
    static final String ADMIN_EMAIL = "3520097134@qq.com";
    static final String ADMIN_PASSWORD = "123456";

    private static final Logger log = LoggerFactory.getLogger(AdminAccountInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminAccountInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        UserEntity admin = userRepository.findByUsername(ADMIN_USERNAME).orElse(null);
        if (admin == null) {
            createAdminAccount();
            return;
        }
        boolean changed = false;
        if (!ADMIN_EMAIL.equals(admin.getEmail())) {
            admin.setEmail(ADMIN_EMAIL);
            changed = true;
        }
        if (!passwordEncoder.matches(ADMIN_PASSWORD, admin.getPassword())) {
            admin.setPassword(passwordEncoder.encode(ADMIN_PASSWORD));
            changed = true;
        }
        if (changed) {
            try {
                userRepository.save(admin);
                log.info("admin account aligned username={} email={}", ADMIN_USERNAME, ADMIN_EMAIL);
            } catch (DataIntegrityViolationException exception) {
                log.warn("admin account alignment failed: email {} already bound to another user", ADMIN_EMAIL);
            }
        }
    }

    private void createAdminAccount() {
        UserEntity admin = new UserEntity();
        admin.setUsername(ADMIN_USERNAME);
        admin.setEmail(ADMIN_EMAIL);
        admin.setPassword(passwordEncoder.encode(ADMIN_PASSWORD));
        admin.setNickname("管理员");
        try {
            userRepository.save(admin);
            log.info("admin account created username={} email={}", ADMIN_USERNAME, ADMIN_EMAIL);
        } catch (DataIntegrityViolationException exception) {
            log.warn("admin account creation failed: username or email {} already taken", ADMIN_EMAIL);
        }
    }
}
