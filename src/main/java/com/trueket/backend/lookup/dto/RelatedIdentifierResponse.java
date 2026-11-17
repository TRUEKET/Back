
package com.trueket.backend.lookup.dto;

import com.trueket.backend.identifier.entity.enums.IdentifierType;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "RP-02 연관 식별자 정보")
public record RelatedIdentifierResponse(

        @Schema(
                description = "연관 식별자 ID",
                example = "309"
        )
        Long identifierId,

        @Schema(
                description = "연관 식별자 종류: ACCOUNT, PHONE, SNS",
                example = "PHONE"
        )
        IdentifierType type,

        @Schema(
                description = "개인정보 보호를 위해 마스킹된 식별자",
                example = "010-****-1234"
        )
        String maskedValue
) {
}
