package com.trueket.backend.report.dto;

import com.trueket.backend.report.entity.Report;
import com.trueket.backend.report.entity.enums.ReportKind;
import com.trueket.backend.report.entity.enums.ReportStatus;
import com.trueket.backend.report.entity.enums.TradeType;

import java.time.OffsetDateTime;

public record ReportListResponse(
        Long reportId,
        ReportKind kind,
        ReportStatus status,
        TradeType tradeType,
        OffsetDateTime createdAt
) {
    public static ReportListResponse from(Report report) {
        return new ReportListResponse(
                report.getId(),
                report.getKind(),
                report.getStatus(),
                report.getTradeType(),
                report.getCreatedAt()
        );
    }
}