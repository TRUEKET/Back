
package com.trueket.backend.lookup.dto;

import com.trueket.backend.report.entity.enums.ReportKind;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "RP-02 신고 타임라인 항목")
public record LookupTimelineResponse(

        @Schema(
                description = "신고 등록 날짜",
                example = "2026-11-15",
                type = "string",
                format = "date"
        )
        LocalDate date,

        @Schema(
                description = "신고 종류: FRAUD(피해 신고), SUSPICION(의심 제보)",
                example = "FRAUD"
        )
        ReportKind kind
) {
}
