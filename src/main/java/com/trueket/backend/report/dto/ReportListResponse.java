
package com.trueket.backend.report.dto;

import com.trueket.backend.dispute.dto.DisputeNoticeResponse;
import com.trueket.backend.report.entity.Report;
import com.trueket.backend.report.entity.enums.ReportKind;
import com.trueket.backend.report.entity.enums.ReportStatus;
import com.trueket.backend.report.entity.enums.TradeType;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "RP-06 내 신고 목록 응답 항목")
public record ReportListResponse(

        @Schema(
                description = "신고 ID",
                example = "365"
        )
        Long reportId,

        @Schema(
                description = "신고 종류: FRAUD(피해 신고), SUSPICION(의심 제보)",
                example = "FRAUD"
        )
        ReportKind kind,

        @Schema(
                description = "신고 상태",
                example = "PUBLISHED"
        )
        ReportStatus status,

        @Schema(
                description = "JD 판독에서 결정된 거래 유형",
                example = "TRANSFER"
        )
        TradeType tradeType,

        @Schema(
                description = "신고 등록 일시 (타임존 포함)",
                example = "2026-11-15T20:00:00+09:00"
        )
        OffsetDateTime createdAt,

        @Schema(
                description = "연결된 RP-08 이의제기 알림 목록. 이의제기가 없으면 빈 배열"
        )
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
