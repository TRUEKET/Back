
package com.trueket.backend.identifier.util;

import com.trueket.backend.identifier.entity.enums.IdentifierType;

public final class IdentifierMasker {

    private IdentifierMasker() {
    }

    public static String mask(
            IdentifierType type,
            String normalizedValue
    ) {
        return switch (type) {
            case PHONE -> {
                String last4 = normalizedValue.substring(
                        normalizedValue.length() - 4
                );
                yield normalizedValue.substring(0, 3)
                        + "-****-" + last4;
            }
            case ACCOUNT -> {
                int separator = normalizedValue.indexOf(':');
                String bank = normalizedValue.substring(0, separator);
                String number = normalizedValue.substring(separator + 1);

                yield bank + "-****-" + number.substring(
                        number.length() - 4
                );
            }
            case SNS -> {
                int length = normalizedValue.length();

                if (length == 1) {
                    yield "@*";
                }

                yield "@" + normalizedValue.charAt(0)
                        + "*".repeat(length - 1);
            }
        };
    }
}
