package com.offerforge.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 非生产（无 redis profile）的兜底发信：日志打印验证码（本地联调/测试）。
 */
@Component
@Profile("!redis")
public class MockVerificationMailSender implements VerificationMailSender {

    private static final Logger log = LoggerFactory.getLogger(MockVerificationMailSender.class);

    /** 仅供测试断言读取最近发送记录 */
    private final List<String[]> sentMails = new CopyOnWriteArrayList<>();

    @Override
    public void sendVerificationCode(String toEmail, String code) {
        sentMails.add(new String[]{toEmail, code});
        log.info("[MOCK-MAIL] verification code email to={} code={}", toEmail, code);
    }

    public List<String[]> getSentMails() {
        return sentMails;
    }
}
