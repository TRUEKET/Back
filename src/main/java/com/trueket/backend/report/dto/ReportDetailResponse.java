package com.trueket.backend.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.trueket.backend.report.entity.Report;
import com.trueket.backend.report.entity.enums.ReportKind;
import com.trueket.backend.report.entity.enums.ReportStatus;
import com.trueket.backend.report.entity.enums.TradeType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record ReportDetailResponse(
        Long reportId,
        ReportKind kind,
        ReportStatus status,
        TradeType tradeType,
        JsonNode evidenceQuotes,
        JsonNode referenceLinks,
        LocalDate sentDate,
        OffsetDateTime createdAt,
        List<ReportIdentifierResponse> identifiers
) {
    public static ReportDetailResponse from(
            Report report,
            List<ReportIdentifierResponse> identifiers
    ) {
        return new ReportDetailResponse(
                report.getId(),
                report.getKind(),
                report.getStatus(),
                report.getTradeType(),
                report.getEvidenceQuotes(),
                report.getReferenceLinks(),
                report.getSentDate(),
                report.getCreatedAt(),
                List.copyOf(identifiers)
        );
    }
}