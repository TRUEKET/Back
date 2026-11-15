
package com.trueket.backend.dispute.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "이의제기 가능 여부 조회 응답")
public record DisputeAvailabilityResponse(

        @Schema(
                description = "신청 가능 상태. NEW_REPORTS_ONLY는 기존 이의제기 이후 새로운 신고가 존재하는 경우",
                example = "NEW_REPORTS_ONLY"
        )
        String status,

        @Schema(
                description = "이의제기 신청 가능 여부",
                example = "true"
        )
        boolean canApply,

        @Schema(
                description = "이번 이의제기의 대상이 되는 신고 수",
                example = "3"
        )
        int targetReportCount
) {}
