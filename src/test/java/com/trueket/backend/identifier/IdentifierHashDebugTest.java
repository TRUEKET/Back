
package com.trueket.backend.identifier;

import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.identifier.repository.IdentifierRepository;
import com.trueket.backend.identifier.service.IdentifierEncryptionService;
import com.trueket.backend.identifier.util.IdentifierHashService;
import com.trueket.backend.identifier.util.IdentifierNormalizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IdentifierHashDebugTest {

    @Autowired
    private IdentifierRepository identifierRepository;

    @Autowired
    private IdentifierEncryptionService encryptionService;

    @Autowired
    private IdentifierHashService hashService;

    @Test
    void verifyStoredIdentifierHash() {

        // 1. DB에서 식별자 308 조회
        Identifier identifier = identifierRepository.findById(308L)
                .orElseThrow(() ->
                        new IllegalStateException("식별자 308을 찾을 수 없습니다.")
                );

        // 2. 암호화된 원본 계좌번호 복호화
        String decryptedValue = encryptionService.decrypt(
                identifier.getValueEnc()
        );

        // 3. 복호화된 값으로 해시 재계산
        String recalculatedHash = hashService.hash(
                identifier.getType(),
                decryptedValue
        );

        // 4. DB에 저장된 해시와 비교
        boolean storedHashMatches =
                recalculatedHash.equals(identifier.getValueHash());

        // 5. Postman 검색값 정규화 및 해시 계산
        String lookupInput = "신한 111-111-111111";

        var normalized = IdentifierNormalizer.normalize(lookupInput);

        String lookupHash = hashService.hash(
                normalized.type(),
                normalized.value()
        );

        boolean lookupHashMatches =
                lookupHash.equals(identifier.getValueHash());

        // 6. 결과 출력 (원본 계좌번호 및 해시 출력하지 않음)
        System.out.println("========== RP-01 디버깅 ==========");
        System.out.println("DB 식별자 ID: " + identifier.getId());
        System.out.println("복호화 성공: true");
        System.out.println("DB 저장 해시 재계산 일치: " + storedHashMatches);
        System.out.println("Postman 입력 해시 일치: " + lookupHashMatches);
        System.out.println("=================================");

        // 저장된 데이터 자체의 정합성 검증
        assertThat(storedHashMatches).isTrue();
    }
}
