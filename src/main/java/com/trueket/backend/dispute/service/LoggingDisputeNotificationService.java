package com.trueket.backend.dispute.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Slf4j
@Service
@ConditionalOnProperty(
        prefix = "dispute.mail",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class LoggingDisputeNotificationService
        implements DisputeNotificationService {

    @Override
    public void sendReceivedEmail(
            String sellerEmail,
            String receiptNo
    ) {
        log.info("[메일 미발송] 접수 안내: {}", receiptNo);
    }

    @Override
    public void sendDeliveredEmail(
            String sellerEmail,
            String receiptNo,
            int reportCount,
            OffsetDateTime deadline
    ) {
        log.info(
                "[메일 미발송] 전달 안내: {}, {}건, 기한={}",
                receiptNo, reportCount, deadline
        );
    }

    @Override
    public void sendRejectedEmail(
            String sellerEmail,
            String receiptNo
    ) {
        log.info("[메일 미발송] 증빙 반려: {}", receiptNo);
    }

    @Override
    public void sendReporterDisputeEmail(
            Long reportId,
            Long disputeId
    ) {
        log.info(
                "[메일 미발송] 게시자 이의제기 알림: reportId={}, disputeId={}",
                reportId, disputeId
        );
    }

    @Override
    public void sendResultEmail(
            String sellerEmail,
            String receiptNo,
            int maintainedCount,
            int deletedCount,
            int privateCount
    ) {
        log.info(
                "[메일 미발송] 결과 안내: {}, 유지={}, 삭제={}, 비공개={}",
                receiptNo, maintainedCount, deletedCount, privateCount
        );
    }
}