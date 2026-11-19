package com.trueket.backend.analyze.dto;

import com.trueket.backend.report.entity.enums.TradeType;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public record AnalyzeResponse(@Schema(description="신고/피드백 연결에 쓰는 임시 판독 ID. readable=false이면 null") UUID analyzeId, @Schema(description="이미지에서 거래 대화를 읽었는지. false이면 다시 캡처해야 합니다.") boolean readable, TradeType detectedType,
                              FinalAssessment assessment, List<MaskedIdentifier> identifiers,
                              String otherSuspicious, String message, ConversationDetails conversations) {
    @com.fasterxml.jackson.annotation.JsonProperty("resultTitle")
    @Schema(description="결과 화면용 안내 문구. SAFE는 안전 보증 대신 확인된 위험 신호 없음으로 표시", accessMode=Schema.AccessMode.READ_ONLY)
    public String resultTitle() {
        if (!readable || assessment == null) return "대화를 읽지 못했어요";
        return switch (assessment.finalStage()) {
            case SAFE -> "확인된 위험 신호 없음";
            case SUSPECT -> "주의가 필요한 신호를 확인했어요";
            case DANGER -> "위험 신호를 확인했어요";
        };
    }

    @com.fasterxml.jackson.annotation.JsonProperty("limitationNotice")
    @Schema(description="결과 상단에 함께 표시할 판독 한계 안내", accessMode=Schema.AccessMode.READ_ONLY)
    public String limitationNotice() {
        return "제공된 대화와 확인 가능한 신고 기록을 바탕으로 한 참고 결과예요. 위험 신호가 없더라도 거래의 안전이나 티켓의 진위를 보장하지 않아요.";
    }

    public record MaskedIdentifier(String type, @Schema(description="화면용 마스킹 값. 계좌는 은행·앞부분과 마지막 6자리 가림, 전화는 010-****-1234 형식. RP-05에는 이 문자열 대신 ACCOUNT 순서의 accountIndex를 전달", example="신한 110-22-******") String value) {}
}
