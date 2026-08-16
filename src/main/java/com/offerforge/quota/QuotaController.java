package com.offerforge.quota;

import com.offerforge.apikey.ApiKeyService;
import com.offerforge.auth.CurrentUserService;
import com.offerforge.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 免费额度查询接口：供前端面试页展示三种状态
 * （有自带 Key 无限制 / 无 Key 剩余额度 / 无 Key 额度耗尽）。
 */
@RestController
@RequestMapping("/api/quota")
public class QuotaController {

    private final QuotaService quotaService;
    private final ApiKeyService apiKeyService;
    private final CurrentUserService currentUserService;

    /** 额度视图：remaining 在额度关闭时等于 dailyLimit */
    public record QuotaInfo(boolean hasOwnKey, int remaining, int dailyLimit, boolean enabled) {
    }

    public QuotaController(QuotaService quotaService,
                           ApiKeyService apiKeyService,
                           CurrentUserService currentUserService) {
        this.quotaService = quotaService;
        this.apiKeyService = apiKeyService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public ApiResponse<QuotaInfo> quota(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(new QuotaInfo(
                apiKeyService.hasKey(userId),
                quotaService.checkQuota(userId),
                quotaService.dailyLimit(),
                quotaService.isEnabled()));
    }
}
