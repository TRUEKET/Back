
package com.trueket.backend.analyze.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "JD 주요 대화 상세 판독 정보")
public record ReportEvidenceDetail(

        @Schema(description = "사진 번호 (1부터 시작)", example = "1")
        int imageIndex,

        @Schema(description = "사기 판단 신호 ID", example = "S-B3")
        String signalId,

        @Schema(
                description = "마스킹된 근거 문장",
                example = "본명 말고 닉네임으로 입금해주세요."
        )
        String quote,

        @Schema(description = "신호 위험도", example = "CAUTION")
        SignalStrength severity,

        @Schema(description = "의심 행동 유형", example = "수상한 송금 요구")
        String action,

        @Schema(
                description = "해당 신호의 판단 이유",
                example = "입금자 확인을 피하려는 말입니다."
        )
        String reason,

        @Schema(
                description = "위험도 계산에서 제외된 참고 신호 여부",
                example = "false"
        )
        boolean informational,

        @Schema(description = "유사 피해 사례 목록. 없으면 빈 배열")
        List<FraudCase> cases

) {
    public ReportEvidenceDetail {
        cases = cases == null
                ? List.of()
                : List.copyOf(cases);
    }
}
