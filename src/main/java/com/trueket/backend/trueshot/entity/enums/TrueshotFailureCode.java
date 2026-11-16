package com.trueket.backend.trueshot.entity.enums;

public enum TrueshotFailureCode {
    REQUEST_TIMEOUT("인증 링크의 유효 시간이 지났어요. 구매자에게 새 링크를 요청해 주세요."),
    FIRST_PHOTO_TIMEOUT("첫 번째 사진 제출 시간이 지났어요. 구매자에게 새 링크를 요청해 주세요."),
    SECOND_PHOTO_TIMEOUT("두 번째 사진 제출 시간이 지났어요. 구매자에게 새 링크를 요청해 주세요."),
    ANALYSIS_TIMEOUT("사진 분석이 지연되어 제한 시간 안에 확인하지 못했어요. 새 인증 링크를 요청해 주세요."),
    VERIFICATION_FAILED("사진 인증을 통과하지 못했어요. 실패 사유를 확인하고 새 인증 링크를 요청해 주세요.");

    private final String message;

    TrueshotFailureCode(String message) { this.message = message; }
    public String message() { return message; }
    public boolean isTimeout() { return this != VERIFICATION_FAILED; }
}
