
package com.trueket.backend.lookup.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "RP-03 식별자 전체 값 비교 요청")
public record LookupCompareRequest(

        @NotBlank(message = "비교할 값을 입력해 주세요.")
        @Schema(
                description = "비교할 은행명+전체 계좌번호, 전화번호 또는 SNS 계정",
                example = "신한 111-111-1-111111",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String value
) {
}
