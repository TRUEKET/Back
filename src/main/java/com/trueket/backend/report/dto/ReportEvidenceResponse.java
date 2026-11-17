
package com.trueket.backend.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "RP-05 송금 증빙 검증 결과")
public record ReportEvidenceResponse(

        @Schema(
                description = "송금 증빙 검증 성공 여부. 실패 시 예외 응답 반환",
                example = "true"
        )
        boolean verified,

        @Schema(
                description = "송금 증빙 검증 결과 안내 메시지",
                example = "송금 내역의 수취 계좌가 신고 대상과 일치합니다."
        )
        String message,

        @Schema(
                description = "RP-04 신고 등록 시 사용하는 임시 증빙 토큰",
                example = "550e8400-e29b-41d4-a716-446655440000"
        )
        UUID evidenceToken

) {
}
