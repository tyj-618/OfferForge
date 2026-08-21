package com.offerforge.email;

import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * 邮箱验证码服务：生成 6 位数字码 → 腾讯云 SES 发送 → Redis 存储（TTL 5 分钟）。
 * 防刷：同一邮箱 60 秒内仅可发送一次；错误超过 5 次锁定 15 分钟。
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final EmailVerificationCodeStore codeStore;
    private final VerificationMailSender mailSender;
    private final SecureRandom secureRandom = new SecureRandom();

    public EmailService(EmailVerificationCodeStore codeStore, VerificationMailSender mailSender) {
        this.codeStore = codeStore;
        this.mailSender = mailSender;
    }

    /** 生成 6 位数字验证码（含前导零，SecureRandom 防猜测） */
    public String generateCode() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    /** 校验邮箱格式 */
    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    /**
     * 发送验证码：锁定检查 → 防刷检查 → 生成 → 发信 → 存储。
     * 发信失败不落库，用户可立即重试。
     */
    public void sendVerificationCode(String email) {
        if (!isValidEmail(email)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "邮箱格式不正确");
        }
        if (codeStore.isLocked(email)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "错误次数过多，请 15 分钟后再试");
        }
        if (codeStore.hasRecentSendMark(email)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "发送过于频繁，请 60 秒后再试");
        }
        String code = generateCode();
        mailSender.sendVerificationCode(email, code);
        codeStore.saveCode(email, code);
        codeStore.markSent(email);
        log.info("verification code sent email={}", email);
    }

    /**
     * 校验验证码：锁定 → 取码比对；正确删除并清除错误计数，错误累计并在超限时锁定。
     */
    public boolean verifyCode(String email, String code) {
        if (codeStore.isLocked(email)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "错误次数过多，请 15 分钟后再试");
        }
        String stored = codeStore.getCode(email);
        if (stored == null || code == null || !stored.equals(code.trim())) {
            boolean locked = codeStore.recordFailure(email);
            log.warn("verification code mismatch email={} locked={}", email, locked);
            return false;
        }
        codeStore.removeCode(email);
        codeStore.clearFailures(email);
        log.info("verification code matched email={}", email);
        return true;
    }
}
