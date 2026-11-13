
package com.trueket.backend.lookup.util;

import com.trueket.backend.identifier.entity.enums.IdentifierType;

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

    private static final Map<String, String> BANK_NAMES = Map.ofEntries(
            Map.entry("KB", "KB"),
            Map.entry("국민", "KB"),
            Map.entry("신한", "SHINHAN"),
            Map.entry("우리", "WOORI"),
            Map.entry("하나", "HANA"),
            Map.entry("농협", "NH"),
            Map.entry("NH", "NH"),
            Map.entry("기업", "IBK"),
            Map.entry("IBK", "IBK"),
            Map.entry("카카오", "KAKAO"),
            Map.entry("토스", "TOSS")
    );

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
        for (Map.Entry<String, String> bank : BANK_NAMES.entrySet()) {

            String bankName = bank.getKey();

            if (!value.toUpperCase(Locale.ROOT)
                    .startsWith(bankName.toUpperCase(Locale.ROOT))) {
                continue;
            }

            String account = value.substring(bankName.length())
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
