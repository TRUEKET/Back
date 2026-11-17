
package com.trueket.backend.lookup.dto;

import com.trueket.backend.identifier.entity.enums.IdentifierType;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "RP-01 신고 리스트 조회 응답")
public record LookupResponse(

        @Schema(
                description = "검색된 식별자 ID. 검색 결과가 없으면 null",
                example = "308",
                nullable = true
        )
        Long identifierId,

        @Schema(
                description = "식별자 종류: ACCOUNT, PHONE, SNS",
                example = "ACCOUNT"
        )
        IdentifierType type,

        @Schema(
                description = "검색된 식별자의 정규화 값. 검색 결과가 없으면 null",
                example = "SHINHAN:111111111111",
                nullable = true
        )
        String value,

        @Schema(
                description = "위험도: DANGER, SUSPICION, SAFE",
                allowableValues = {"DANGER", "SUSPICION", "SAFE"},
                example = "DANGER"
        )
        String riskLevel,

        @Schema(
                description = "최근 1년간 유효한 피해 신고자 수 (동일 휴대폰 중복 제거)",
                example = "1"
        )
        long fraudCount,

        @Schema(
                description = "최근 1년간 유효한 의심 제보자 수 (동일 휴대폰 중복 제거)",
                example = "2"
        )
        long suspicionCount
) {
}
