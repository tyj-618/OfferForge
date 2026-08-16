package com.offerforge.apikey;

import com.offerforge.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiKeyService 行为：加密 upsert、解密返回、状态不含明文、参数校验。
 */
class ApiKeyServiceTest {

    private ApiKeyRepository repository;
    private TestEncryptor encryptor;
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        repository = mock(ApiKeyRepository.class);
        encryptor = new TestEncryptor();
        service = new ApiKeyService(repository, encryptor);
        when(repository.findByUserId(anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void saveQianwenStoresEncryptedKeyWithFixedBaseUrlAndDefaultModel() {
        when(repository.save(org.mockito.ArgumentMatchers.any(ApiKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApiKeyService.ApiKeyStatus status = service.save(1L, "QIANWEN", null, null, "sk-user-key");

        assertThat(status.configured()).isTrue();
        assertThat(status.provider()).isEqualTo("QIANWEN");
        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(repository).save(captor.capture());
        ApiKey saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getBaseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(saved.getModel()).isEqualTo("qwen-plus");
        // 密文绝不等于明文（测试加密器加前缀模拟）
        assertThat(saved.getEncryptedKey()).isEqualTo("enc:sk-user-key").isNotEqualTo("sk-user-key");
    }

    @Test
    void saveOpenAiCompatibleRequiresBaseUrlAndModel() {
        assertThatThrownBy(() -> service.save(1L, "OPENAI_COMPATIBLE", null, "gpt-4o", "sk-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("baseUrl");
        assertThatThrownBy(() -> service.save(1L, "OPENAI_COMPATIBLE", "https://203.0.113.10", null, "sk-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("model");
    }

    @Test
    void saveRejectsUnsafeBaseUrl() {
        // 非 https 拒绝
        assertThatThrownBy(() -> service.save(1L, "OPENAI_COMPATIBLE", "http://203.0.113.10/v1", "gpt-4o", "sk-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("https");
        // 环回地址拒绝
        assertThatThrownBy(() -> service.save(1L, "OPENAI_COMPATIBLE", "https://127.0.0.1/v1", "gpt-4o", "sk-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("本地或内网");
        // 私有网段拒绝
        assertThatThrownBy(() -> service.save(1L, "OPENAI_COMPATIBLE", "https://192.168.1.10/v1", "gpt-4o", "sk-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("本地或内网");
        // 链路本地（云元数据）地址拒绝
        assertThatThrownBy(() -> service.save(1L, "OPENAI_COMPATIBLE", "https://169.254.169.254/v1", "gpt-4o", "sk-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("本地或内网");
    }

    @Test
    void saveRejectsInvalidProviderBlankKeyAndOversizedKey() {
        assertThatThrownBy(() -> service.save(1L, "UNKNOWN", null, null, "sk-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("provider");
        assertThatThrownBy(() -> service.save(1L, "QIANWEN", null, null, "  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("apiKey");
        assertThatThrownBy(() -> service.save(1L, "QIANWEN", null, null, "sk-" + "x".repeat(300)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("200");
    }

    @Test
    void saveUpdatesExistingEntityInsteadOfInsertingNewRow() {
        ApiKey existing = new ApiKey();
        existing.setId(99L);
        existing.setUserId(1L);
        when(repository.findByUserId(1L)).thenReturn(Optional.of(existing));
        when(repository.save(org.mockito.ArgumentMatchers.any(ApiKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.save(1L, "OPENAI_COMPATIBLE", "https://203.0.113.10/v1", "gpt-4o", "sk-new");

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(99L);
        assertThat(captor.getValue().getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void getKeyReturnsDecryptedCredentials() {
        ApiKey entity = new ApiKey();
        entity.setUserId(1L);
        entity.setProvider("QIANWEN");
        entity.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        entity.setModel("qwen-plus");
        entity.setEncryptedKey("enc:sk-user-key");
        when(repository.findByUserId(1L)).thenReturn(Optional.of(entity));

        Optional<ApiKeyService.DecryptedApiKey> key = service.getKey(1L);

        assertThat(key).isPresent();
        assertThat(key.get().apiKey()).isEqualTo("sk-user-key");
        assertThat(key.get().provider()).isEqualTo("QIANWEN");
    }

    @Test
    void getKeyReturnsEmptyWhenNotConfigured() {
        assertThat(service.getKey(1L)).isEmpty();
        assertThat(service.hasKey(1L)).isFalse();
    }

    @Test
    void statusOnlyExposesProviderNeverPlainText() {
        ApiKey entity = new ApiKey();
        entity.setUserId(1L);
        entity.setProvider("OPENAI_COMPATIBLE");
        entity.setEncryptedKey("enc:sk-user-key");
        when(repository.findByUserId(1L)).thenReturn(Optional.of(entity));

        ApiKeyService.ApiKeyStatus status = service.status(1L);

        assertThat(status.configured()).isTrue();
        assertThat(status.provider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(service.status(2L).configured()).isFalse();
        assertThat(service.status(2L).provider()).isNull();
    }

    /** 测试加密器：加 enc: 前缀模拟加密，解密去前缀 */
    private static class TestEncryptor extends ApiKeyEncryptor {
        TestEncryptor() {
            super(testProperties());
        }

        @Override
        public String encrypt(String plainText) {
            return "enc:" + plainText;
        }

        @Override
        public String decrypt(String encrypted) {
            return encrypted.substring("enc:".length());
        }

        private static EncryptionProperties testProperties() {
            EncryptionProperties properties = new EncryptionProperties();
            properties.setKey("offerforge-test-aes256-gcm-key!!");
            return properties;
        }
    }
}
