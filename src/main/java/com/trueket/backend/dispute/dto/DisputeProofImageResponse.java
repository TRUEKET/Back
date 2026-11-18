
package com.trueket.backend.dispute.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "마스킹된 이의제기 증빙 사진")
public record DisputeProofImageResponse(

        @Schema(
                description = "증빙 사진 유형",
                allowableValues = {
                        "DELIVERY",
                        "TRANSFER",
                        "REFUND"
                },
                example = "REFUND"
        )
        String type,

        @Schema(
                description = "300초 동안 유효한 Signed URL. "
                        + "만료 시 신고 상세 조회 API 재요청",
                format = "uri",
                example = "https://example.supabase.co/storage/v1/object/sign/dispute-evidence/19/example.png?token=example"
        )
        String imageUrl

) {
}
