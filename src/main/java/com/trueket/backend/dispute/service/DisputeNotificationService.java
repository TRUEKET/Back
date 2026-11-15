package com.trueket.backend.dispute.service;

import java.time.OffsetDateTime;

public interface DisputeNotificationService {

    // 판매자: 접수 안내
    void sendReceivedEmail(
            String sellerEmail,
            String receiptNo
    );

    // 판매자: 게시자에게 전달 완료
    void sendDeliveredEmail(
            String sellerEmail,
            String receiptNo,
            int reportCount,
            OffsetDateTime deadline
    );

    // 판매자: AI 증빙 반려
    void sendRejectedEmail(
            String sellerEmail,
            String receiptNo
    );

    // 신고 게시자: 이의제기 안내
    void sendReporterDisputeEmail(
            Long reportId,
            Long disputeId
    );

    // 판매자: 모든 기록의 처리 결과
    void sendResultEmail(
            String sellerEmail,
            String receiptNo,
            int maintainedCount,
            int deletedCount,
            int privateCount
    );
}