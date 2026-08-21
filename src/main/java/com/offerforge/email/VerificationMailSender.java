package com.offerforge.email;

/**
 * 验证码邮件发送抽象：生产走腾讯云 SES，未配置凭据或测试环境走 Mock。
 */
public interface VerificationMailSender {

    /** 发送验证码邮件；发送失败抛运行时异常由上层统一处理 */
    void sendVerificationCode(String toEmail, String code);
}
