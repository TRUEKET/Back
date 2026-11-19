package com.trueket.backend.analyze.service;

import com.trueket.backend.analyze.dto.ModelExtraction;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.identifier.util.IdentifierNormalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Completes explicitly labelled seller fields; never infers identifiers from arbitrary prose. */
final class SupplementalIdentifiers {
    private static final Pattern ACCOUNT = Pattern.compile("(?m)(?:^|[;,])[ \\t]*(?:판매자[ \\t]*)?계좌(?:번호)?[ \\t]*[:：][ \\t]*([^\\r\\n;,]+)");
    private static final Pattern PHONE = Pattern.compile("(?m)(?:^|[;,])[ \\t]*(?:판매자[ \\t]*)?(?:전화번호|휴대폰[ \\t]*번호|연락처)[ \\t]*[:：][ \\t]*([^\\r\\n;,]+)");
    private SupplementalIdentifiers() {}

    static ModelExtraction merge(ModelExtraction result, String additionalInfo) {
        if (!result.readable() || additionalInfo == null || additionalInfo.isBlank()) return result;
        var accounts = new ArrayList<>(result.extracted().accounts());
        var phones = new ArrayList<>(result.extracted().phones());
        var sensitive = new LinkedHashSet<>(result.sensitiveValues());
        append(ACCOUNT, additionalInfo, IdentifierType.ACCOUNT, accounts, sensitive);
        append(PHONE, additionalInfo, IdentifierType.PHONE, phones, sensitive);
        return new ModelExtraction(result.readable(), result.detectedType(), result.typeMismatch(), result.sentences(),
                result.signals(), new ModelExtraction.Extracted(List.copyOf(accounts), List.copyOf(phones),
                result.extracted().xAccounts(), result.extracted().links()), result.otherSuspicious(), List.copyOf(sensitive));
    }

    private static void append(Pattern pattern, String input, IdentifierType type, List<String> values, Set<String> sensitive) {
        var fields = pattern.matcher(input);
        while (fields.find()) {
            String raw = fields.group(1).strip();
            try {
                var normalized = IdentifierNormalizer.normalize(raw);
                if (normalized.type() != type) continue;
                boolean exists = values.stream().anyMatch(value -> same(value, normalized));
                if (!exists && values.size() >= 10) continue;
                if (!exists) values.add(type == IdentifierType.PHONE ? normalized.value() : raw);
                sensitive.add(raw);
                if (type == IdentifierType.PHONE) sensitive.add(normalized.value());
                else {
                    var number = Pattern.compile("[0-9][0-9\\s-]*$").matcher(raw);
                    if (number.find()) sensitive.add(number.group().strip());
                    sensitive.add(normalized.value().substring(normalized.value().indexOf(':') + 1));
                }
            } catch (IllegalArgumentException ignored) {
                // Missing bank, masked number, prose or buyer-labelled fields are not repaired by guessing.
            }
        }
    }

    private static boolean same(String value, IdentifierNormalizer.NormalizedIdentifier wanted) {
        try { return IdentifierNormalizer.normalize(value).equals(wanted); }
        catch (IllegalArgumentException e) { return false; }
    }
}
