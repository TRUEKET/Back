package com.trueket.backend.report.service;

import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.identifier.service.IdentifierEncryptionService;
import com.trueket.backend.identifier.util.IdentifierNormalizer;
import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.report.dto.ReportIdentifierResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.Locale;
import java.util.Map;

/** Called only after the service has scoped reports to the authenticated owner. */
@Component
public class OwnedReportIdentifierMapper {
    private static final Map<String,String> BANKS=Map.of("KB","국민","SHINHAN","신한","WOORI","우리",
            "HANA","하나","NH","농협","IBK","기업","KAKAO","카카오","TOSS","토스");
    private final IdentifierEncryptionService encryption;
    public OwnedReportIdentifierMapper(IdentifierEncryptionService encryption) { this.encryption=encryption; }

    public ReportIdentifierResponse map(Identifier identifier) {
        try {
            String plaintext=encryption.decrypt(identifier.getValueEnc());
            String value=switch(identifier.getType()) {
                case ACCOUNT -> account(plaintext);
                case PHONE -> {
                    var normalized=IdentifierNormalizer.normalize(plaintext);
                    if(normalized.type()!=IdentifierType.PHONE) throw new IllegalArgumentException();
                    String number=normalized.value();
                    yield number.substring(0,3)+"-"+number.substring(3,number.length()-4)+"-"+number.substring(number.length()-4);
                }
                case SNS -> {
                    var normalized=IdentifierNormalizer.normalize(plaintext.startsWith("@")?plaintext:"@"+plaintext);
                    if(normalized.type()!=IdentifierType.SNS) throw new IllegalArgumentException();
                    yield "@"+normalized.value();
                }
            };
            return new ReportIdentifierResponse(identifier.getId(),identifier.getType().name(),identifier.getValueMasked(),value);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Corrupt or legacy ciphertext cannot be "unmasked" by guessing or leaking internal details.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"신고 식별자 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
    }

    private String account(String plaintext) {
        String canonical=plaintext;
        if(!plaintext.matches("[A-Za-z]+:[0-9]{8,16}")) {
            var normalized=IdentifierNormalizer.normalize(plaintext);
            if(normalized.type()!=IdentifierType.ACCOUNT) throw new IllegalArgumentException();
            canonical=normalized.value();
        }
        int split=canonical.indexOf(':');
        String bank=BANKS.get(canonical.substring(0,split).toUpperCase(Locale.ROOT));
        if(bank==null) throw new IllegalArgumentException();
        return bank+" "+canonical.substring(split+1);
    }
}
