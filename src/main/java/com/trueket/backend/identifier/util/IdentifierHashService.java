
package com.trueket.backend.identifier.util;

import com.trueket.backend.identifier.entity.enums.IdentifierType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Component
public class IdentifierHashService {

    private final byte[] secretKey;

    public IdentifierHashService(
            @Value("${security.identifier-hash-secret}")
            String secret
    ) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "식별자 해시 비밀키가 설정되지 않았습니다."
            );
        }

        this.secretKey = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(
            IdentifierType type,
            String normalizedValue
    ) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");

            mac.init(
                    new SecretKeySpec(secretKey, "HmacSHA256")
            );

            String input = type.name() + ":" + normalizedValue;

            byte[] result = mac.doFinal(
                    input.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(result);

        } catch (Exception e) {
            throw new IllegalStateException(
                    "식별자 해시 생성에 실패했습니다.", e
            );
        }
    }
}
