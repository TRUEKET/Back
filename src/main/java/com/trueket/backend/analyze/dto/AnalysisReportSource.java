package com.trueket.backend.analyze.dto;

import com.trueket.backend.report.entity.enums.TradeType;
import java.util.List;
import java.util.UUID;

/** Server-internal input for report creation; never return this as an HTTP response. */
public record AnalysisReportSource(UUID analysisId, long userId, TradeType tradeType,
                                   Identifiers identifiers, List<ChatAssessment.Evidence> evidence) {
    public AnalysisReportSource { evidence = List.copyOf(evidence); }

    /** Unmasked values for exact identifier matching, not for public evidence or logs. */
    public record Identifiers(List<String> accounts, List<String> phones, List<String> xAccounts) {
        public Identifiers {
            accounts = List.copyOf(accounts);
            phones = List.copyOf(phones);
            xAccounts = List.copyOf(xAccounts);
        }
        @Override public String toString() { return "Identifiers[redacted]"; }
    }
}
