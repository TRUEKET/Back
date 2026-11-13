
package com.trueket.backend.lookup.util;

import com.trueket.backend.identifier.entity.enums.IdentifierType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class IdentifierNormalizerTest {

    @Test
    @DisplayName("전화번호 - 하이픈을 제거하고 PHONE으로 분류한다")
    void normalizePhoneWithHyphen() {

        var result = IdentifierNormalizer.normalize(
                "010-1234-5678"
        );

        assertThat(result.type())
                .isEqualTo(IdentifierType.PHONE);

        assertThat(result.value())
                .isEqualTo("01012345678");
    }

    @Test
    @DisplayName("전화번호 - 숫자만 입력해도 PHONE으로 분류한다")
    void normalizePhoneWithoutHyphen() {

        var result = IdentifierNormalizer.normalize(
                "01012345678"
        );

        assertThat(result.type())
                .isEqualTo(IdentifierType.PHONE);

        assertThat(result.value())
                .isEqualTo("01012345678");
    }

    @Test
    @DisplayName("X 계정 - @를 제거하고 소문자로 변환한다")
    void normalizeSns() {

        var result = IdentifierNormalizer.normalize(
                "@TrueKet_User"
        );

        assertThat(result.type())
                .isEqualTo(IdentifierType.SNS);

        assertThat(result.value())
                .isEqualTo("trueket_user");
    }

    @Test
    @DisplayName("X 계정 - 숫자가 포함되어 있어도 SNS로 분류한다")
    void normalizeSnsWithNumber() {

        var result = IdentifierNormalizer.normalize(
                "@Trueket123"
        );

        assertThat(result.type())
                .isEqualTo(IdentifierType.SNS);

        assertThat(result.value())
                .isEqualTo("trueket123");
    }

    @Test
    @DisplayName("계좌번호 - KB 은행명과 계좌번호를 정규화한다")
    void normalizeKbAccount() {

        var result = IdentifierNormalizer.normalize(
                "KB 123-456-789012"
        );

        assertThat(result.type())
                .isEqualTo(IdentifierType.ACCOUNT);

        assertThat(result.value())
                .isEqualTo("KB:123456789012");
    }

    @Test
    @DisplayName("계좌번호 - 국민과 KB는 동일하게 정규화한다")
    void normalizeKbAlias() {

        var kb = IdentifierNormalizer.normalize(
                "KB 123-456-789012"
        );

        var kookmin = IdentifierNormalizer.normalize(
                "국민 123-456-789012"
        );

        assertThat(kb).isEqualTo(kookmin);
    }

    @Test
    @DisplayName("계좌번호 - 신한은행 계좌를 정규화한다")
    void normalizeShinhanAccount() {

        var result = IdentifierNormalizer.normalize(
                "신한 110-123-456789"
        );

        assertThat(result.type())
                .isEqualTo(IdentifierType.ACCOUNT);

        assertThat(result.value())
                .isEqualTo("SHINHAN:110123456789");
    }

    @Test
    @DisplayName("계좌번호 - NH와 농협은 동일하게 정규화한다")
    void normalizeNhAlias() {

        var nh = IdentifierNormalizer.normalize(
                "NH 123-456-789012"
        );

        var nonghyup = IdentifierNormalizer.normalize(
                "농협 123-456-789012"
        );

        assertThat(nh).isEqualTo(nonghyup);
    }

    @Test
    @DisplayName("전화번호 - 앞뒤 공백을 제거한다")
    void normalizePhoneWithSpaces() {

        var result = IdentifierNormalizer.normalize(
                " 010-1234-5678 "
        );

        assertThat(result.type())
                .isEqualTo(IdentifierType.PHONE);

        assertThat(result.value())
                .isEqualTo("01012345678");
    }

    @Test
    @DisplayName("계좌번호 - 앞자리 0을 유지한다")
    void preserveLeadingZero() {

        var result = IdentifierNormalizer.normalize(
                "KB 001-234-567890"
        );

        assertThat(result.value())
                .isEqualTo("KB:001234567890");
    }

    @Test
    @DisplayName("빈 입력값은 예외를 발생시킨다")
    void rejectBlankInput() {

        assertThatThrownBy(() ->
                IdentifierNormalizer.normalize(" ")
        )
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("불완전한 전화번호는 예외를 발생시킨다")
    void rejectIncompletePhone() {

        assertThatThrownBy(() ->
                IdentifierNormalizer.normalize("010-1234")
        )
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("은행명이 없는 계좌번호는 예외를 발생시킨다")
    void rejectAccountWithoutBank() {

        assertThatThrownBy(() ->
                IdentifierNormalizer.normalize("123456789012")
        )
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("@가 없는 X 계정은 예외를 발생시킨다")
    void rejectSnsWithoutAt() {

        assertThatThrownBy(() ->
                IdentifierNormalizer.normalize("Trueket_User")
        )
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("지원하지 않는 은행명은 예외를 발생시킨다")
    void rejectUnknownBank() {

        assertThatThrownBy(() ->
                IdentifierNormalizer.normalize(
                        "UNKNOWN 123-456-789012"
                )
        )
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("X 계정에 허용되지 않는 문자가 있으면 예외를 발생시킨다")
    void rejectInvalidSns() {

        assertThatThrownBy(() ->
                IdentifierNormalizer.normalize("@Trueket!")
        )
                .isInstanceOf(IllegalArgumentException.class);
    }
}
