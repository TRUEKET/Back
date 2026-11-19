package com.trueket.backend.report.dto;

import com.trueket.backend.identifier.entity.Identifier;

@io.swagger.v3.oas.annotations.media.Schema(description="본인 신고 전용 식별자 응답. 공개 조회 DTO와 구분")
public record ReportIdentifierResponse(
        Long identifierId,
        String type,
        @io.swagger.v3.oas.annotations.media.Schema(description="호환용 마스킹 값. 내 신고 화면은 value를 사용하세요.") String maskedValue,
        @io.swagger.v3.oas.annotations.media.Schema(description="로그인한 본인 신고에서만 제공하는 전체 식별자. 계좌는 은행명+전체 숫자, 전화는 전체 번호, SNS는 @계정", example="신한 11022123456") String value
) {
    public static ReportIdentifierResponse from(
            Identifier identifier
    ) {
        return new ReportIdentifierResponse(
                identifier.getId(),
                identifier.getType().name(),
                identifier.getValueMasked(),
                null
        );
    }
}