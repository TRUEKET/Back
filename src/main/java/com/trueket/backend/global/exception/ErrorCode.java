
package com.trueket.backend.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_REQUEST(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            "잘못된 요청입니다."
    ),

    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_SERVER_ERROR",
            "서버 내부 오류가 발생했습니다."
    ),

    UNAUTHORIZED(
            HttpStatus.UNAUTHORIZED,
            "UNAUTHORIZED",
            "인증이 필요합니다."
    ),

    FORBIDDEN(
            HttpStatus.FORBIDDEN,
            "FORBIDDEN",
            "접근 권한이 없습니다."
    ),

    RESOURCE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "RESOURCE_NOT_FOUND",
            "요청한 리소스를 찾을 수 없습니다."
    ),

    TOO_MANY_REQUESTS(
            HttpStatus.TOO_MANY_REQUESTS,
            "TOO_MANY_REQUESTS",
            "조회 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."
    ),

    // RP-01
    INVALID_IDENTIFIER(
            HttpStatus.BAD_REQUEST,
            "INVALID_IDENTIFIER",
            "올바른 전화번호, X 계정 또는 은행명과 계좌번호를 입력해 주세요."
    ),

    // RP-02, RP-03
    IDENTIFIER_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "IDENTIFIER_NOT_FOUND",
            "조회 대상 정보를 찾을 수 없습니다."
    ),

    REPORT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
        "REPORT_NOT_FOUND",
                "신고 기록을 찾을 수 없습니다."
    ),

    REPORT_ACCESS_DENIED(
            HttpStatus.FORBIDDEN,
        "REPORT_ACCESS_DENIED",
                "해당 신고 기록에 접근할 권한이 없습니다."
    ),

    // RP-04
    DUPLICATE_REPORT(
            HttpStatus.CONFLICT,
            "DUPLICATE_REPORT",
            "이미 동일한 계좌에 피해 신고를 등록했습니다."
    ),

    REPORT_NOT_ALLOWED(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "REPORT_NOT_ALLOWED",
            "현재 판독 결과로는 신고를 기록할 수 없습니다."
    ),

    ANALYSIS_NOT_AVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "ANALYSIS_NOT_AVAILABLE",
            "판독 결과를 검증할 수 없습니다."
    );


    private final HttpStatus status;
    private final String code;
    private final String message;
}
