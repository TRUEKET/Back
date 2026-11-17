
package com.trueket.backend.dispute.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        description = "이의제기 신청용 multipart/form-data 요청"
)
public record DisputeCreateSwaggerRequest(

        @Schema(
                description = "이의제기 대상 은행명과 전체 계좌번호",
                example = "신한 111-111-1-111111",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String identifier,

        @Schema(
                description = "이의제기 사유. 현재 NORMAL, REFUND만 지원",
                allowableValues = {"NORMAL", "REFUND"},
                example = "NORMAL",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String reason,

        @Schema(
                description = "이의제기 상세 설명 (선택)",
                example = "구매자에게 표를 전달했습니다."
        )
        String description,

        @Schema(
                description = "결과 이메일을 받을 판매자 이메일",
                example = "seller@example.com",
                format = "email",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String sellerEmail,

        @Schema(
                description = "NORMAL에서 필수인 표 전달 증빙 이미지. REFUND에서는 첨부하지 않음. PNG/JPEG, 최대 5MiB",
                type = "string",
                format = "binary"
        )
        String deliveryEvidence,

        @Schema(
                description = "입금 또는 환불 이체확인증. PNG/JPEG, 최대 5MiB",
                type = "string",
                format = "binary",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String transferEvidence
) {
}
