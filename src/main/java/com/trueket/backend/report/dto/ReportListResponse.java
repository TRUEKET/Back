package com.trueket.backend.report.dto;

import com.trueket.backend.dispute.dto.DisputeNoticeResponse;
import com.trueket.backend.report.entity.Report;
import com.trueket.backend.report.entity.enums.ReportKind;
import com.trueket.backend.report.entity.enums.ReportStatus;
import com.trueket.backend.report.entity.enums.TradeType;

import java.time.OffsetDateTime;
import java.util.List;

public record ReportListResponse(
        Long reportId,
        ReportKind kind,
        ReportStatus status,
        TradeType tradeType,
        OffsetDateTime createdAt,
        List<DisputeNoticeResponse> disputes
) {
    public static ReportListResponse from(
            Report report,
            List<DisputeNoticeResponse> disputes
    ) {
        return new ReportListResponse(
                report.getId(),
                report.getKind(),
                report.getStatus(),
                report.getTradeType(),
                report.getCreatedAt(),
                List.copyOf(disputes)
        );
    }

    // 기존 다른 코드와의 호환을 위해 유지
    public static ReportListResponse from(Report report) {
        return from(report, List.of());
    }
}