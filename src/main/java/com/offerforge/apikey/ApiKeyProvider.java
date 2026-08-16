package com.offerforge.apikey;

import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;

/**
 * 支持的用户自带 Key Provider：千问使用固定 Base URL 与缺省模型，
 * OpenAI 兼容接口需用户提供 Base URL 与模型名。
 */
public enum ApiKeyProvider {

    QIANWEN("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    OPENAI_COMPATIBLE(null, null);

    private final String fixedBaseUrl;
    private final String defaultModel;

    ApiKeyProvider(String fixedBaseUrl, String defaultModel) {
        this.fixedBaseUrl = fixedBaseUrl;
        this.defaultModel = defaultModel;
    }

    public String fixedBaseUrl() {
        return fixedBaseUrl;
    }

    public String defaultModel() {
        return defaultModel;
    }

    /**
     * 解析 provider 字符串；非法值返回参数错误。
     */
    public static ApiKeyProvider fromValue(String value) {
        if (value == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "provider 不能为空");
        }
        try {
            return ApiKeyProvider.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "provider 仅支持 QIANWEN / OPENAI_COMPATIBLE");
        }
    }
}
