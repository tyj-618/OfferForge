package com.offerforge.email;

import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EmailService 单元测试：验证码生成格式、防刷、校验消费、错误锁定语义。
 */
class EmailServiceTests {

    private final InMemoryEmailVerificationCodeStore store =
            new InMemoryEmailVerificationCodeStore(Clock.systemUTC());
    private final RecordingSender sender = new RecordingSender();
    private final EmailService emailService = new EmailService(store, sender);

    @Test
    void generatedCodeIsSixDigits() {
        for (int i = 0; i < 50; i++) {
            assertThat(emailService.generateCode()).matches("\\d{6}");
        }
    }

    @Test
    void emailFormatValidation() {
        assertThat(EmailService.isValidEmail("user@example.com")).isTrue();
        assertThat(EmailService.isValidEmail("first.last+tag@mail.example.cn")).isTrue();
        assertThat(EmailService.isValidEmail("no-at-sign.com")).isFalse();
        assertThat(EmailService.isValidEmail("a@b")).isFalse();
        assertThat(EmailService.isValidEmail(null)).isFalse();
    }

    @Test
    void sendRejectsInvalidEmail() {
        assertThatThrownBy(() -> emailService.sendVerificationCode("bad-email"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.PARAM_ERROR);
        assertThat(sender.mails).isEmpty();
    }

    @Test
    void sendStoresCodeAndMarksAntiSpam() {
        emailService.sendVerificationCode("a@example.com");
        assertThat(sender.mails).hasSize(1);
        String code = sender.mails.get(0)[1];
        assertThat(store.getCode("a@example.com")).isEqualTo(code);

        // 60 秒防刷：立即重发被拒
        assertThatThrownBy(() -> emailService.sendVerificationCode("a@example.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        assertThat(sender.mails).hasSize(1);
    }

    @Test
    void verifyCorrectCodeConsumesIt() {
        emailService.sendVerificationCode("b@example.com");
        String code = sender.mails.get(0)[1];

        assertThat(emailService.verifyCode("b@example.com", code)).isTrue();
        // 验证成功后验证码即失效（防重放）
        assertThat(emailService.verifyCode("b@example.com", code)).isFalse();
    }

    @Test
    void wrongCodeAfterFiveFailuresLocksEmail() {
        emailService.sendVerificationCode("c@example.com");
        String code = sender.mails.get(0)[1];

        // 前 5 次错误仅返回 false，第 6 次触发锁定
        for (int i = 0; i < 6; i++) {
            assertThat(emailService.verifyCode("c@example.com", "000000".equals(code) ? "111111" : "000000"))
                    .isFalse();
        }
        assertThat(store.isLocked("c@example.com")).isTrue();

        // 锁定后校验与发信均被拒绝
        assertThatThrownBy(() -> emailService.verifyCode("c@example.com", code))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        assertThatThrownBy(() -> emailService.sendVerificationCode("c@example.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }

    @Test
    void successfulVerifyClearsFailureCount() {
        emailService.sendVerificationCode("d@example.com");
        String code = sender.mails.get(0)[1];
        emailService.verifyCode("d@example.com", "wrong1");
        emailService.verifyCode("d@example.com", "wrong2");

        assertThat(emailService.verifyCode("d@example.com", code)).isTrue();
        assertThat(store.isLocked("d@example.com")).isFalse();
    }

    /** 记录发信行为的测试替身 */
    static class RecordingSender implements VerificationMailSender {
        final List<String[]> mails = new ArrayList<>();

        @Override
        public void sendVerificationCode(String toEmail, String code) {
            mails.add(new String[]{toEmail, code});
        }
    }
}
