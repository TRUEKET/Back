package com.trueket.backend.analyze.dto;

import com.trueket.backend.report.entity.enums.TradeType;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public record AnalyzeResponse(@Schema(description="신고/피드백 연결에 쓰는 임시 판독 ID. readable=false이면 null") UUID analyzeId, @Schema(description="이미지에서 거래 대화를 읽었는지. false이면 다시 캡처해야 합니다.") boolean readable, TradeType detectedType,
                              FinalAssessment assessment, List<MaskedIdentifier> identifiers,
                              String otherSuspicious, String message, ConversationDetails conversations) {
    public record MaskedIdentifier(String type, @Schema(description="화면용 마스킹 값. 계좌는 은행·앞부분과 마지막 6자리 가림, 전화는 010-****-1234 형식. RP-05에는 이 문자열 대신 ACCOUNT 순서의 accountIndex를 전달", example="신한 110-22-******") String value) {}
}
