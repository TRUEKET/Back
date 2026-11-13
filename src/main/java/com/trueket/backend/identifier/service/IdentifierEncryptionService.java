
package com.trueket.backend.identifier.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class IdentifierEncryptionService {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public IdentifierEncryptionService(
            @Value("${security.identifier-encryption-key}")
            String encodedKey
    ) {
        byte[] key = Base64.getDecoder().decode(encodedKey);

        if (key.length != 32) {
            throw new IllegalArgumentException(
                    "식별자 암호화 키는 32바이트여야 합니다."
            );
        }

        this.secretKey = new SecretKeySpec(key, "AES");
    }

    public String encrypt(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "암호화할 값이 없습니다."
            );
        }

        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    secretKey,
                    new GCMParameterSpec(TAG_BITS, iv)
            );

            byte[] encrypted = cipher.doFinal(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            return "v1:"
                    + Base64.getEncoder().encodeToString(iv)
                    + ":"
                    + Base64.getEncoder().encodeToString(encrypted);

        } catch (Exception e) {
            throw new IllegalStateException(
                    "식별자 암호화에 실패했습니다.", e
            );
        }
    }

    public String decrypt(String encryptedValue) {
        try {
            String[] parts = encryptedValue.split(":", -1);

            if (parts.length != 3 || !parts[0].equals("v1")) {
                throw new IllegalArgumentException(
                        "지원하지 않는 암호문 형식입니다."
                );
            }

            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getDecoder().decode(parts[2]);

            if (iv.length != IV_LENGTH) {
                throw new IllegalArgumentException(
                        "잘못된 암호문입니다."
                );
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    new GCMParameterSpec(TAG_BITS, iv)
            );

            byte[] decrypted = cipher.doFinal(encrypted);

            return new String(
                    decrypted,
                    StandardCharsets.UTF_8
            );

        } catch (Exception e) {
            throw new IllegalStateException(
                    "식별자 복호화에 실패했습니다.", e
            );
        }
    }
}
