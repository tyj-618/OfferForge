package com.offerforge.email;

import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.ses.v20201002.SesClient;
import com.tencentcloudapi.ses.v20201002.models.SendEmailRequest;
import com.tencentcloudapi.ses.v20201002.models.Simple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 腾讯云 SES 发信实现：仅生产（redis profile）装配，凭据从 tencent.ses.* 配置读取。
 */
@Component
@Profile("redis")
public class TencentSesMailSender implements VerificationMailSender {

    private static final Logger log = LoggerFactory.getLogger(TencentSesMailSender.class);

    private final SesClient client;
    private final TencentSesProperties properties;

    public TencentSesMailSender(TencentSesProperties properties) {
        this.properties = properties;
        Credential credential = new Credential(properties.getSecretId(), properties.getSecretKey());
        HttpProfile httpProfile = new HttpProfile();
        httpProfile.setEndpoint("ses.tencentcloudapi.com");
        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);
        this.client = new SesClient(credential, properties.getRegion(), clientProfile);
        log.info("tencent ses sender initialized region={} from={}", properties.getRegion(),
                properties.getFromEmail());
    }

    @Override
    public void sendVerificationCode(String toEmail, String code) {
        SendEmailRequest request = new SendEmailRequest();
        request.setFromEmailAddress(properties.getFromEmail());
        request.setDestination(new String[]{toEmail});
        request.setSubject("EasyOfferForge 验证码");
        Simple body = new Simple();
        body.setHtml("<p>您的验证码是 <b>" + code + "</b>，5 分钟内有效。请勿泄露给他人。</p>");
        request.setSimple(body);
        try {
            client.SendEmail(request);
            log.info("verification code email sent to={}", toEmail);
        } catch (TencentCloudSDKException exception) {
            log.error("verification code email send failed to={} errorCode={} requestId={}",
                    toEmail, exception.getErrorCode(), exception.getRequestId(), exception);
            throw new IllegalStateException("邮件发送失败：" + exception.getErrorCode(), exception);
        }
    }
}
