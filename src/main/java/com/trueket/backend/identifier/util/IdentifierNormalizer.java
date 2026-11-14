
package com.trueket.backend.identifier.util;

import com.trueket.backend.identifier.entity.enums.IdentifierType;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class IdentifierNormalizer {

    private static final Pattern PHONE =
            Pattern.compile("01[016789]\\d{7,8}");

    private static final Pattern SNS =
            Pattern.compile("[A-Za-z0-9_]{1,15}");

    private static final Pattern ACCOUNT_NUMBER =
            Pattern.compile("\\d{8,16}");

    // 다양한 은행 명칭을 기존 표준 코드로 통일
    private static final Map<String, String> BANK_NAMES = Map.ofEntries(
            Map.entry("KB국민은행", "KB"),
            Map.entry("국민은행", "KB"),
            Map.entry("국민", "KB"),
            Map.entry("KB", "KB"),

            Map.entry("신한은행", "SHINHAN"),
            Map.entry("신한", "SHINHAN"),

            Map.entry("우리은행", "WOORI"),
            Map.entry("우리", "WOORI"),

            Map.entry("하나은행", "HANA"),
            Map.entry("하나", "HANA"),

            Map.entry("NH농협은행", "NH"),
            Map.entry("농협은행", "NH"),
            Map.entry("농협", "NH"),
            Map.entry("NH", "NH"),

            Map.entry("IBK기업은행", "IBK"),
            Map.entry("기업은행", "IBK"),
            Map.entry("기업", "IBK"),
            Map.entry("IBK", "IBK"),

            Map.entry("카카오뱅크", "KAKAO"),
            Map.entry("카카오", "KAKAO"),

            Map.entry("토스뱅크", "TOSS"),
            Map.entry("토스", "TOSS")
    );

    // 접두사가 겹치는 은행명은 긴 이름부터 검사
    private static final List<Map.Entry<String, String>> SORTED_BANKS =
            BANK_NAMES.entrySet()
                    .stream()
                    .sorted(
                            Comparator.<Map.Entry<String, String>>
                                            comparingInt(entry -> entry.getKey().length())
                                    .reversed()
                    )
                    .toList();

    private IdentifierNormalizer() {
    }

    public record NormalizedIdentifier(
            IdentifierType type,
            String value
    ) {
    }

    public static NormalizedIdentifier normalize(String input) {

        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException(
                    "조회할 값을 입력해 주세요."
            );
        }

        String value = input.trim();

        // 1. X 계정
        if (value.startsWith("@")) {

            String username = value.substring(1);

            if (!SNS.matcher(username).matches()) {
                throw new IllegalArgumentException(
                        "올바른 X 계정을 입력해 주세요."
                );
            }

            return new NormalizedIdentifier(
                    IdentifierType.SNS,
                    username.toLowerCase(Locale.ROOT)
            );
        }

        // 2. 전화번호
        String digits = value.replaceAll("[\\s-]", "");

        if (PHONE.matcher(digits).matches()
                && value.matches("[0-9\\s-]+")) {

            return new NormalizedIdentifier(
                    IdentifierType.PHONE,
                    digits
            );
        }

        // 3. 계좌번호 (은행명 + 계좌번호)
        for (Map.Entry<String, String> bank : SORTED_BANKS) {

            String bankName = bank.getKey();

            if (!value.regionMatches(
                    true,
                    0,
                    bankName,
                    0,
                    bankName.length()
            )) {
                continue;
            }

            String account = value
                    .substring(bankName.length())
                    .replaceAll("[\\s-]", "");

            if (!ACCOUNT_NUMBER.matcher(account).matches()) {
                continue;
            }

            return new NormalizedIdentifier(
                    IdentifierType.ACCOUNT,
                    bank.getValue() + ":" + account
            );
        }

        throw new IllegalArgumentException(
                "올바른 전화번호, X 계정 또는 은행명과 계좌번호를 입력해 주세요."
        );
    }
}
