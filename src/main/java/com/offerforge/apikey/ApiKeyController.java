package com.offerforge.apikey;

import com.offerforge.auth.CurrentUserService;
import com.offerforge.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户自带 API Key 管理接口。身份一律从登录态注入；
 * GET 只返回 provider 状态，任何接口都不返回明文 Key。
 */
@RestController
@RequestMapping("/api/apikey")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final CurrentUserService currentUserService;

    /** 保存/更新请求：provider 必填；OPENAI_COMPATIBLE 时 baseUrl/model 必填 */
    public record SaveApiKeyRequest(String provider, String baseUrl, String model, String apiKey) {
    }

    public ApiKeyController(ApiKeyService apiKeyService, CurrentUserService currentUserService) {
        this.apiKeyService = apiKeyService;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    public ApiResponse<ApiKeyService.ApiKeyStatus> save(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody SaveApiKeyRequest request) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(apiKeyService.save(
                userId, request.provider(), request.baseUrl(), request.model(), request.apiKey()));
    }

    @GetMapping
    public ApiResponse<ApiKeyService.ApiKeyStatus> status(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(apiKeyService.status(userId));
    }

    @DeleteMapping
    public ApiResponse<Void> delete(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = currentUserService.requireUserId(authorization);
        apiKeyService.delete(userId);
        return ApiResponse.success(null);
    }
}
