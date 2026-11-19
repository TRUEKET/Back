package com.trueket.backend.analyze.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Tolerates formatting only; returns a contiguous span of the actual source text. */
final class QuoteMatcher {
    private QuoteMatcher() {}

    static Optional<String> findOriginal(String sentence, String quote) {
        if (sentence == null || quote == null) return Optional.empty();
        Normalized wanted = normalize(quote);
        if (wanted.text().isEmpty()) return Optional.empty();
        if (sentence.contains(quote)) return Optional.of(quote);
        Normalized source = normalize(sentence);
        int start = source.text().indexOf(wanted.text());
        if (start < 0) return Optional.empty();
        int originalStart = source.starts().get(start);
        int originalEnd = source.ends().get(start + wanted.text().length() - 1);
        return Optional.of(sentence.substring(originalStart, originalEnd));
    }

    private static boolean digitBeside(String text, int offset, int direction) {
        while (direction < 0 ? offset > 0 : offset < text.length()) {
            int cp = direction < 0 ? text.codePointBefore(offset) : text.codePointAt(offset);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) return Character.isDigit(cp);
            offset += direction * Character.charCount(cp);
        }
        return false;
    }

    private record Normalized(String text, List<Integer> starts, List<Integer> ends) {}

    private static Normalized normalize(String text) {
        var normalized = new StringBuilder();
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            int end = offset + Character.charCount(cp);
            // Decimal/group separators inside numbers are data, not formatting noise.
            boolean numericSeparator = (cp == '.' || cp == ',') && offset > 0 && end < text.length()
                    && digitBeside(text, offset, -1) && digitBeside(text, end, 1);
            boolean ignored = Character.isWhitespace(cp) || Character.isSpaceChar(cp)
                    || (!numericSeparator && ".,! ?~…\"'‘’“”「」『』。！？～".indexOf(cp) >= 0);
            if (!ignored) {
                normalized.appendCodePoint(cp);
                for (int i = offset; i < end; i++) {
                    starts.add(offset);
                    ends.add(end);
                }
            }
            offset = end;
        }
        return new Normalized(normalized.toString(), starts, ends);
    }
}
