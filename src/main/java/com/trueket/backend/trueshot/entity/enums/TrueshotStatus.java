package com.trueket.backend.trueshot.entity.enums;

public enum TrueshotStatus {
    // 구매자 화면: 촬영 대기. 판매자가 아직 시작하지 않은 상태.
    PENDING,
    // 구매자 화면: 촬영 대기. 사진 확인과 판매자의 사진 공유 확인을 포함하는 상태.
    IN_PROGRESS,
    // 구매자 화면: 인증 성공. 사진 공유 후 또는 30분 내 미공유 시 결과만 확정한 상태.
    PASSED,
    // 구매자 화면: 인증 실패. 모든 시간 초과를 포함하며 별도의 만료 상태는 두지 않음.
    FAILED
}
