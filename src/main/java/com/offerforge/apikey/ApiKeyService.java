package com.offerforge.apikey;

import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Optional;

/**
 * 用户自带 API Key 管理：保存（加密 upsert）/ 查询（解密）/ 删除。
 * 对外状态查询只暴露 provider，绝不返回明文 Key。
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    /** API Key 明文长度上限（防异常超长输入） */
    private static final int MAX_KEY_LENGTH = 200;

    private final ApiKeyRepository repository;
    private final ApiKeyEncryptor encryptor;

    /** 解密后的完整凭据（仅服务内部传递，禁止进入日志与响应） */
    public record DecryptedApiKey(String provider, String baseUrl, String model, String apiKey) {
    }

    /** 对外状态视图：只含是否配置与 provider */
    public record ApiKeyStatus(boolean configured, String provider) {
    }

    public ApiKeyService(ApiKeyRepository repository, ApiKeyEncryptor encryptor) {
        this.repository = repository;
        this.encryptor = encryptor;
    }

    /**
     * 保存/更新 Key：千问固定 Base URL（模型缺省 qwen-plus）；OpenAI 兼容必须提供 Base URL 与模型。
     */
    @Transactional
    public ApiKeyStatus save(Long userId, String providerValue, String baseUrl, String model, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "apiKey 不能为空");
        }
        String trimmedKey = apiKey.trim();
        if (trimmedKey.length() > MAX_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "apiKey 长度不能超过 " + MAX_KEY_LENGTH + " 字符");
        }
        ApiKeyProvider provider = ApiKeyProvider.fromValue(providerValue);
        String effectiveBaseUrl;
        String effectiveModel;
        if (provider == ApiKeyProvider.QIANWEN) {
            effectiveBaseUrl = provider.fixedBaseUrl();
            effectiveModel = isBlank(model) ? provider.defaultModel() : model.trim();
        } else {
            if (isBlank(baseUrl)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "OpenAI 兼容接口需提供 baseUrl");
            }
            if (isBlank(model)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "OpenAI 兼容接口需提供 model");
            }
            validateBaseUrl(baseUrl.trim());
            effectiveBaseUrl = baseUrl.trim();
            effectiveModel = model.trim();
        }

        ApiKey entity = repository.findByUserId(userId).orElseGet(ApiKey::new);
        entity.setUserId(userId);
        entity.setProvider(provider.name());
        entity.setBaseUrl(effectiveBaseUrl);
        entity.setModel(effectiveModel);
        entity.setEncryptedKey(encryptor.encrypt(trimmedKey));
        repository.save(entity);
        log.info("apikey saved userId={} provider={} model={}", userId, provider.name(), effectiveModel);
        return new ApiKeyStatus(true, provider.name());
    }

    /**
     * 解密返回用户凭据；未配置返回空。
     */
    public Optional<DecryptedApiKey> getKey(Long userId) {
        return repository.findByUserId(userId).map(entity -> new DecryptedApiKey(
                entity.getProvider(),
                entity.getBaseUrl(),
                entity.getModel(),
                encryptor.decrypt(entity.getEncryptedKey())));
    }

    public boolean hasKey(Long userId) {
        return repository.existsByUserId(userId);
    }

    /**
     * 对外状态：未配置返回 configured=false 且不带 provider。
     */
    public ApiKeyStatus status(Long userId) {
        return repository.findByUserId(userId)
                .map(entity -> new ApiKeyStatus(true, entity.getProvider()))
                .orElse(new ApiKeyStatus(false, null));
    }

    @Transactional
    public void delete(Long userId) {
        repository.deleteByUserId(userId);
        log.info("apikey deleted userId={}", userId);
    }

    /**
     * baseUrl 安全校验（防 SSRF）：强制 https，拒绝指向环回/链路本地/内网/云元数据等私有地址，
     * 避免服务端被当作代理探测内网。
     */
    private void validateBaseUrl(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "baseUrl 格式不合法");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "baseUrl 必须使用 https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "baseUrl 缺少主机名");
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "baseUrl 主机名无法解析");
        }
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "baseUrl 不允许指向本地或内网地址");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
