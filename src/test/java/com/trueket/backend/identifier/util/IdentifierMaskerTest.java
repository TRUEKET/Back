
package com.trueket.backend.identifier.util;

import com.trueket.backend.identifier.entity.enums.IdentifierType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class IdentifierMaskerTest {

    // ==========================
    // 1. 전화번호 마스킹
    // ==========================

    @Test
    @DisplayName("전화번호는 앞 3자리와 뒤 4자리만 표시한다")
    void maskPhone() {
        String result = IdentifierMasker.mask(
                IdentifierType.PHONE,
                "01012341234"
        );

        assertThat(result)
                .isEqualTo("010-****-1234");
    }

    @Test
    @DisplayName("다른 전화번호도 동일한 형식으로 마스킹한다")
    void maskAnotherPhone() {
        String result = IdentifierMasker.mask(
                IdentifierType.PHONE,
                "0111234567"
        );

        assertThat(result)
                .isEqualTo("011-****-4567");
    }

    // ==========================
    // 2. 계좌번호 마스킹
    // ==========================

    @Test
    @DisplayName("신한은행 계좌는 은행명과 앞부분을 표시하고 뒤 6자리를 가린다")
    void maskShinhanAccount() {
        String result = IdentifierMasker.mask(
                IdentifierType.ACCOUNT,
                "SHINHAN:11022345678"
        );

        assertThat(result)
                .isEqualTo("신한 110-22-******");
    }

    @Test
    @DisplayName("KB 계좌는 은행명과 앞부분을 표시하고 뒤 6자리를 가린다")
    void maskKbAccount() {
        String result = IdentifierMasker.mask(
                IdentifierType.ACCOUNT,
                "KB:12345604123456"
        );

        assertThat(result)
                .isEqualTo("KB 123-456-04-******");
    }

    @Test
    @DisplayName("8자리 계좌번호도 마지막 6자리를 가린다")
    void maskShortAccount() {
        String result = IdentifierMasker.mask(
                IdentifierType.ACCOUNT,
                "WOORI:12345678"
        );

        assertThat(result)
                .isEqualTo("우리 12-******");
    }

    // ==========================
    // 3. X 계정 마스킹
    // ==========================

    @Test
    @DisplayName("X 계정은 앞 8글자만 표시하고 나머지를 가린다")
    void maskSns() {
        String result = IdentifierMasker.mask(
                IdentifierType.SNS,
                "hello123world"
        );

        assertThat(result)
                .isEqualTo("@hello123*****");
    }

    @Test
    @DisplayName("8글자 이하 X 계정도 최소 한 글자는 가린다")
    void maskShortSns() {
        String result = IdentifierMasker.mask(
                IdentifierType.SNS,
                "hello123"
        );

        assertThat(result)
                .isEqualTo("@hello12*");
    }

    @Test
    @DisplayName("한 글자 X 계정은 전체를 가린다")
    void maskSingleCharacterSns() {
        String result = IdentifierMasker.mask(
                IdentifierType.SNS,
                "a"
        );

        assertThat(result)
                .isEqualTo("@*");
    }

    @Test
    @DisplayName("@가 포함된 X 계정도 중복 없이 마스킹한다")
    void maskSnsWithAtSign() {
        String result = IdentifierMasker.mask(
                IdentifierType.SNS,
                "@hello123world"
        );

        assertThat(result)
                .isEqualTo("@hello123*****");
    }

    // ==========================
    // 4. 잘못된 입력 검증
    // ==========================

    @Test
    @DisplayName("null 식별자 값은 거부한다")
    void rejectNullValue() {
        assertThatThrownBy(() ->
                IdentifierMasker.mask(
                        IdentifierType.PHONE,
                        null
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("빈 식별자 값은 거부한다")
    void rejectBlankValue() {
        assertThatThrownBy(() ->
                IdentifierMasker.mask(
                        IdentifierType.PHONE,
                        " "
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null 식별자 유형은 거부한다")
    void rejectNullType() {
        assertThatThrownBy(() ->
                IdentifierMasker.mask(
                        null,
                        "01012341234"
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("은행 구분자가 없는 계좌는 거부한다")
    void rejectAccountWithoutSeparator() {
        assertThatThrownBy(() ->
                IdentifierMasker.mask(
                        IdentifierType.ACCOUNT,
                        "11022345678"
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("지원하지 않는 은행 코드는 거부한다")
    void rejectUnsupportedBank() {
        assertThatThrownBy(() ->
                IdentifierMasker.mask(
                        IdentifierType.ACCOUNT,
                        "UNKNOWN:12345678901"
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("숫자가 아닌 계좌번호는 거부한다")
    void rejectInvalidAccountNumber() {
        assertThatThrownBy(() ->
                IdentifierMasker.mask(
                        IdentifierType.ACCOUNT,
                        "SHINHAN:1234ABCD5678"
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("잘못된 전화번호는 거부한다")
    void rejectInvalidPhone() {
        assertThatThrownBy(() ->
                IdentifierMasker.mask(
                        IdentifierType.PHONE,
                        "123456"
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
