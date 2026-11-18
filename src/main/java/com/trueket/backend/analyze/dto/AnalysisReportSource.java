
package com.trueket.backend.analyze.dto;

import com.trueket.backend.report.entity.enums.TradeType;

import java.util.List;
import java.util.UUID;

/**
 * 신고 등록에 사용되는 서버 내부 데이터.
 * HTTP 응답으로 직접 반환하지 않는다.
 */
public record AnalysisReportSource(
        UUID analysisId,
        long userId,
        TradeType tradeType,
        Identifiers identifiers,
        List<ChatAssessment.Evidence> evidence,
        List<ReportEvidenceDetail> evidenceDetails,
        boolean showReportButton,
        boolean showSuspicionReport
) {

    public AnalysisReportSource {
        evidence = List.copyOf(evidence);

        evidenceDetails = evidenceDetails == null
                ? List.of()
                : List.copyOf(evidenceDetails);
    }

    // 기존 생성자 호환성 유지
    public AnalysisReportSource(
            UUID analysisId,
            long userId,
            TradeType tradeType,
            Identifiers identifiers,
            List<ChatAssessment.Evidence> evidence,
            boolean showReportButton,
            boolean showSuspicionReport
    ) {
        this(
                analysisId,
                userId,
                tradeType,
                identifiers,
                evidence,
                List.of(),
                showReportButton,
                showSuspicionReport
        );
    }

    /**
     * 식별자 일치 여부 확인에만 사용하는 원본 데이터.
     * 외부 응답 및 로그에 노출하지 않는다.
     */
    public record Identifiers(
            List<String> accounts,
            List<String> phones,
            List<String> xAccounts
    ) {
        public Identifiers {
            accounts = List.copyOf(accounts);
            phones = List.copyOf(phones);
            xAccounts = List.copyOf(xAccounts);
        }

        @Override
        public String toString() {
            return "Identifiers[redacted]";
        }
    }
}
