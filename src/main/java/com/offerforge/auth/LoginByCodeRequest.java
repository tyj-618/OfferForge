package com.offerforge.auth;

import jakarta.validation.constraints.NotBlank;

/** 邮箱验证码登录请求（邮箱未注册时自动创建账号） */
public record LoginByCodeRequest(
        @NotBlank(message = "邮箱不能为空") String email,
        @NotBlank(message = "验证码不能为空") String code) {
}
