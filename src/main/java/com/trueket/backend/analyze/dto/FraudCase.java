
package com.trueket.backend.analyze.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "사기 판단 신호와 관련된 실제 피해 사례")
public record FraudCase(

        @Schema(description = "관련 사기 신호 ID", example = "S-B3")
        String signalId,

        @Schema(
                description = "피해 사례 요약",
                example = "닉네임 입금과 2차 송금 요구로 36만원 피해 발생"
        )
        String summary,

        @Schema(description = "기사 출처", example = "아시아경제")
        String source,

        @Schema(
                description = "기사 발행 날짜",
                type = "string",
                format = "date",
                example = "2026-02-09"
        )
        String publishedAt,

        @Schema(
                description = "기사 원문 URL",
                format = "uri"
        )
        String url

) {
}