package com.offerforge.email;

import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.ses.v20201002.SesClient;
import com.tencentcloudapi.ses.v20201002.models.SendEmailRequest;
import com.tencentcloudapi.ses.v20201002.models.Simple;
import com.tencentcloudapi.ses.v20201002.models.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
        // SES API 要求显式指定触发类型：1=触发邮件（事务类验证码）
        request.setTriggerType(1L);
        if (properties.getTemplateId() != null) {
            // 模板发送：默认账号未开通「自定义发送」权限，必须使用控制台已审核通过的模板，
            // 模板内容中以 {code} 变量占位验证码；直发内容（Simple）要求 HTML base64 编码。
            Template template = new Template();
            template.setTemplateID(properties.getTemplateId());
            template.setTemplateData("{\"code\":\"" + code + "\"}");
            request.setTemplate(template);
        } else {
            Simple body = new Simple();
            body.setHtml(Base64.getEncoder().encodeToString(
                    ("<p>您的验证码是 <b>" + code + "</b>，5 分钟内有效。请勿泄露给他人。</p>")
                            .getBytes(StandardCharsets.UTF_8)));
            request.setSimple(body);
        }
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
