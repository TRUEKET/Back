
package com.trueket.backend.dispute.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "신고 유지·삭제 또는 재게시 처리 응답")
public record DisputeDecisionResponse(

        @Schema(description = "이의제기 ID", example = "18")
        Long disputeId,

        @Schema(description = "대상 신고 ID", example = "361")
        Long reportId,

        @Schema(
                description = "처리 결과",
                example = "MAINTAINED"
        )
        String decision,

        @Schema(
                description = "처리 결과 안내 메시지",
                example = "신고가 다시 공개되었습니다."
        )
        String message
) {}
