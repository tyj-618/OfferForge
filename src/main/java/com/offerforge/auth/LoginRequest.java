package com.offerforge.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 32, message = "长度不能超过 32")
        String username,

        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过 64")
        String password
) {
}
