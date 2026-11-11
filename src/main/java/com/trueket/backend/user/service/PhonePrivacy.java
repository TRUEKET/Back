package com.trueket.backend.user.service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.trueket.backend.global.exception.ApiException;
import org.springframework.http.HttpStatus;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
@Component
public class PhonePrivacy {
    private final byte[] encryptionKey;
    private final byte[] hashKey;
    public PhonePrivacy(@Value("${auth.phone.encryption-key}") String encryptionKey,
                        @Value("${auth.phone.hash-key}") String hashKey) {
        this.encryptionKey = Base64.getDecoder().decode(encryptionKey);
        this.hashKey = Base64.getDecoder().decode(hashKey);
        if (this.encryptionKey.length != 32 || this.hashKey.length < 32) throw new IllegalArgumentException("전화번호 키는 32바이트 이상이 필요합니다. AES 키는 32바이트여야 합니다.");
    }
    public String normalize(String phone) {
        String normalized = phone.replace("-", "");
        if (!normalized.matches("010[0-9]{8}")) throw new ApiException(HttpStatus.BAD_REQUEST, "휴대폰 번호를 정확히 넣어 주세요");
        return normalized;
    }
    public String hash(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hashKey, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public String encrypt(String phone) {
        try {
            byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, iv));
            return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(cipher.doFinal(phone.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
