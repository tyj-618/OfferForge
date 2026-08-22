package com.offerforge.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 128, message = "长度不能超过 128") // 账号字段兼容用户名或邮箱，上限对齐邮箱长度
        String username,

        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过 64")
        String password
) {
}
