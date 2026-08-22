package com.offerforge.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 忘记密码重置请求：邮箱 + 验证码 + 新密码；验证码正确才允许改密。
 */
public record ResetPasswordRequest(
        @NotBlank(message = "不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "长度不能超过 128")
        String email,

        @NotBlank(message = "不能为空")
        @Pattern(regexp = "\\d{6}", message = "验证码为 6 位数字")
        String code,

        @NotBlank(message = "不能为空")
        @Size(min = 6, max = 64, message = "长度需在 6-64 之间")
        String newPassword
) {
}
