package com.offerforge.apikey;

import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API Key 加密器：AES-256-GCM（认证加密，防篡改）。
 * 密文格式 base64(iv || ciphertext || tag)，每次加密随机生成 12 字节 IV，
 * 相同明文两次加密结果不同。密钥来自环境变量，启动时校验长度（必须 32 字节）。
 */
@Component
public class ApiKeyEncryptor {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyEncryptor(EncryptionProperties properties) {
        byte[] keyBytes = properties.getKey() == null
                ? new byte[0]
                : properties.getKey().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "offerforge.encryption.key 必须为 32 字节（环境变量 APIKEY_ENCRYPT_KEY），当前长度 " + keyBytes.length);
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plainText) {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(cipherText, 0, payload, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "API Key 加密失败");
        }
    }

    public String decrypt(String encrypted) {
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(encrypted);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "API Key 密文格式非法");
        }
        if (payload.length <= IV_LENGTH_BYTES) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "API Key 密文格式非法");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, payload, 0, IV_LENGTH_BYTES));
            byte[] plain = cipher.doFinal(payload, IV_LENGTH_BYTES, payload.length - IV_LENGTH_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (AEADBadTagException exception) {
            // 密文被篡改或密钥不匹配：不暴露技术细节
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "API Key 解密失败，请重新配置");
        } catch (GeneralSecurityException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "API Key 解密失败，请重新配置");
        }
    }
}
