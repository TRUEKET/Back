package com.trueket.backend.dispute.entity.enums;

public enum DisputeStatus {
    CHECKING,   // AI-04 증빙 확인 중
    DELIVERED,  // 증빙 통과, 게시자에게 전달
    REJECTED,    // 증빙 반려
    FAILED      //서버가 증빙 확인을 완료 못 함
}