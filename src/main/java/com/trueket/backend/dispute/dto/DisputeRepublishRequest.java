
package com.trueket.backend.dispute.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "자동 비공개된 신고의 재게시 요청")
public record DisputeRepublishRequest(

        @NotNull
        @Schema(
                description = "재게시할 신고 ID. 비공개 후 1회만 재게시 가능",
                example = "361",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Long reportId
) {}
