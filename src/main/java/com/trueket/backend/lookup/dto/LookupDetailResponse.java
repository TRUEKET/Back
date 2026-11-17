
package com.trueket.backend.lookup.dto;

import com.trueket.backend.identifier.entity.enums.IdentifierType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "RP-02 신고 리스트 상세 조회 응답")
public record LookupDetailResponse(

        @Schema(
                description = "조회 대상 식별자 ID",
                example = "308"
        )
        Long identifierId,

        @Schema(
                description = "식별자 종류: ACCOUNT, PHONE, SNS",
                example = "ACCOUNT"
        )
        IdentifierType type,

        @Schema(
                description = "개인정보 보호를 위해 마스킹된 식별자",
                example = "신한 111-111-******"
        )
        String maskedValue,

        @Schema(
                description = "신고 리스트 기준 위험도",
                allowableValues = {"DANGER", "SUSPICION", "SAFE"},
                example = "DANGER"
        )
        String riskLevel,

        @Schema(
                description = "최근 1년간 피해 신고자 수",
                example = "1"
        )
        long fraudCount,

        @Schema(
                description = "최근 1년간 의심 제보자 수",
                example = "2"
        )
        long suspicionCount,

        @Schema(
                description = "서로 다른 신고자 2명 이상과 함께 등장한 연관 식별자 목록"
        )
        List<RelatedIdentifierResponse> relatedIdentifiers,

        @Schema(
                description = "최근 1년간 유효한 신고 타임라인 (최신순)"
        )
        List<LookupTimelineResponse> timeline
) {
}
