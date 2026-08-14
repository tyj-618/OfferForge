package com.offerforge.qa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QaRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 500, message = "长度不能超过 500")
        String question
) {
}
