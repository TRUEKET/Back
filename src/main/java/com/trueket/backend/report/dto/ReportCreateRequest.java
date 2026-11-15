
package com.trueket.backend.report.dto;

import com.trueket.backend.report.entity.enums.ReportKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "RP-04 신고·제보 등록 요청")
public record ReportCreateRequest(

        @NotNull
        @Schema(
                description = "JD 판독 API에서 발급한 analysisId",
                example = "89ee4f8f-c83c-4038-a2a9-403e155b20e4",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        UUID analysisId,

        @NotNull
        @Schema(
                description = "신고 종류: FRAUD(피해 신고), SUSPICION(의심 제보)",
                example = "FRAUD",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        ReportKind kind,

        @Schema(
                description = "송금 날짜 (선택)",
                example = "2026-11-15",
                type = "string",
                format = "date"
        )
        LocalDate sentDate,

        @Schema(
                description = "신고 관련 참고 링크 목록 (선택)",
                example = "[\"https://example.com/reference\"]"
        )
        List<String> referenceLinks,

        @Schema(
                description = "RP-05 송금 증빙 검증 후 발급받은 일회용 토큰 (선택)",
                example = "550e8400-e29b-41d4-a716-446655440000"
        )
        UUID evidenceToken
) {
}
