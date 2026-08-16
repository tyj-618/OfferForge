package com.offerforge.apikey;

import com.offerforge.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AES-256-GCM 加解密行为：往返一致、随机 IV、篡改与错误密钥拒绝。
 */
class ApiKeyEncryptorTest {

    private static final String TEST_KEY = "offerforge-test-aes256-gcm-key!!";

    @Test
    void encryptThenDecryptReturnsOriginalPlainText() {
        ApiKeyEncryptor encryptor = encryptor(TEST_KEY);

        String encrypted = encryptor.encrypt("sk-my-secret-api-key");

        assertThat(encrypted).isNotBlank().doesNotContain("sk-my-secret-api-key");
        assertThat(encryptor.decrypt(encrypted)).isEqualTo("sk-my-secret-api-key");
    }

    @Test
    void samePlainTextProducesDifferentCiphertextsDueToRandomIv() {
        ApiKeyEncryptor encryptor = encryptor(TEST_KEY);

        String first = encryptor.encrypt("sk-same-key");
        String second = encryptor.encrypt("sk-same-key");

        assertThat(first).isNotEqualTo(second);
        assertThat(encryptor.decrypt(first)).isEqualTo("sk-same-key");
        assertThat(encryptor.decrypt(second)).isEqualTo("sk-same-key");
    }

    @Test
    void tamperedCiphertextFailsDecryption() {
        ApiKeyEncryptor encryptor = encryptor(TEST_KEY);
        String encrypted = encryptor.encrypt("sk-original");
        // 翻转最后一个字符破坏 GCM 认证标签
        char last = encrypted.charAt(encrypted.length() - 1);
        String tampered = encrypted.substring(0, encrypted.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThatThrownBy(() -> encryptor.decrypt(tampered))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    void wrongEncryptionKeyFailsDecryption() {
        String encrypted = encryptor(TEST_KEY).encrypt("sk-cross-key");

        ApiKeyEncryptor other = encryptor("another-32-byte-key-for-test!!!!");

        assertThatThrownBy(() -> other.decrypt(encrypted))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    void malformedCiphertextRejected() {
        ApiKeyEncryptor encryptor = encryptor(TEST_KEY);

        assertThatThrownBy(() -> encryptor.decrypt("不是合法的base64!!!"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> encryptor.decrypt("YWJj"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("密文格式非法");
    }

    @Test
    void constructorRejectsKeyWithWrongLength() {
        assertThatThrownBy(() -> encryptor("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    private ApiKeyEncryptor encryptor(String key) {
        EncryptionProperties properties = new EncryptionProperties();
        properties.setKey(key);
        return new ApiKeyEncryptor(properties);
    }
}
