
package com.trueket.backend.dispute.dto;

import com.trueket.backend.dispute.entity.enums.DisputeItemStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "신고 게시자의 이의제기 결정 요청")
public record DisputeDecisionRequest(

        @NotNull
        @Schema(
                description = "결정할 신고 ID",
                example = "359",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Long reportId,

        @NotNull
        @Schema(
                description = "결정 상태. MAINTAINED(신고 유지) 또는 DELETED(신고 삭제)만 허용",
                example = "MAINTAINED",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        DisputeItemStatus decision
) {}
