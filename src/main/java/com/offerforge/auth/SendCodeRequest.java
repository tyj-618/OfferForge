package com.offerforge.auth;

import jakarta.validation.constraints.NotBlank;

/** 发送邮箱验证码请求 */
public record SendCodeRequest(@NotBlank(message = "邮箱不能为空") String email) {
}
