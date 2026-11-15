
package com.trueket.backend.dispute.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "이의제기 접수 응답")
public record DisputeCreateResponse(

        @Schema(description = "생성된 이의제기 ID", example = "19")
        Long disputeId,

        @Schema(description = "이의제기 접수번호", example = "TK-2611-000007")
        String receiptNo,

        @Schema(
                description = "이의제기 처리 상태 (CHECKING, DELIVERED, REJECTED, FAILED)",
                example = "DELIVERED"
        )
        String status,

        @Schema(description = "이의제기가 전달된 신고 수", example = "3")
        int targetReportCount,

        @Schema(
                description = "처리 결과 안내 메시지",
                example = "증빙 검증을 통과하여 이의제기가 전달되었습니다."
        )
        String message
) {}
