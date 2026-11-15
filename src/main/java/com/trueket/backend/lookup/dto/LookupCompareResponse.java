
package com.trueket.backend.lookup.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "RP-03 식별자 전체 값 비교 결과")
public record LookupCompareResponse(

        @Schema(
                description = "입력값과 저장된 식별자의 해시 일치 여부",
                example = "true"
        )
        boolean matched,

        @Schema(
                description = "일치 시 정규화된 사용자 입력값. 불일치 시 null",
                example = "SHINHAN:111111111111",
                nullable = true
        )
        String value
) {
}
