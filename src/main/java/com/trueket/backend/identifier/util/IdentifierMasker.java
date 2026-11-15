
package com.trueket.backend.identifier.util;

import com.trueket.backend.identifier.entity.enums.IdentifierType;

import java.util.Map;

public final class IdentifierMasker {

    private static final Map<String, String> BANK_LABELS = Map.of(
            "KB", "KB",
            "SHINHAN", "신한",
            "WOORI", "우리",
            "HANA", "하나",
            "NH", "농협",
            "IBK", "기업",
            "KAKAO", "카카오",
            "TOSS", "토스"
    );

    private IdentifierMasker() {
    }

    public static String mask(
            IdentifierType type,
            String normalizedValue
    ) {
        if (type == null || normalizedValue == null
                || normalizedValue.isBlank()) {
            throw new IllegalArgumentException("식별자 값이 올바르지 않습니다.");
        }

        return switch (type) {
            case PHONE -> maskPhone(normalizedValue);
            case ACCOUNT -> maskAccount(normalizedValue);
            case SNS -> maskSns(normalizedValue);
        };
    }

    private static String maskPhone(String value) {
        if (!value.matches("01[016789]\\d{7,8}")) {
            throw new IllegalArgumentException("전화번호 형식이 올바르지 않습니다.");
        }

        return value.substring(0, 3)
                + "-****-"
                + value.substring(value.length() - 4);
    }

    private static String maskAccount(String value) {
        int separator = value.indexOf(':');

        if (separator <= 0) {
            throw new IllegalArgumentException("계좌번호 형식이 올바르지 않습니다.");
        }

        String bankCode = value.substring(0, separator);
        String number = value.substring(separator + 1);

        if (!number.matches("\\d{8,16}")) {
            throw new IllegalArgumentException("계좌번호 형식이 올바르지 않습니다.");
        }

        String bank = BANK_LABELS.get(bankCode);

        if (bank == null) {
            throw new IllegalArgumentException("지원하지 않는 은행입니다.");
        }

        int visibleLength = number.length() - 6;
        String visible = number.substring(0, visibleLength);

        // UI 예시처럼 앞부분을 3자리 단위로 구분
        String formatted = visible.replaceAll("(.{3})(?=.)", "$1-");

        return bank + " " + formatted + "-******";
    }

    private static String maskSns(String value) {
        String username = value.startsWith("@")
                ? value.substring(1)
                : value;

        int visibleLength = Math.min(8, username.length());

        // 짧은 계정도 최소 한 글자는 가림
        if (visibleLength == username.length()) {
            visibleLength = Math.max(0, username.length() - 1);
        }

        return "@"
                + username.substring(0, visibleLength)
                + "*".repeat(username.length() - visibleLength);
    }
}
