package com.trueket.backend.dispute.scheduler;

import com.trueket.backend.dispute.entity.enums.DisputeStatus;
import com.trueket.backend.dispute.repository.DisputeRepository;
import com.trueket.backend.dispute.service.DisputeNotificationService;
import com.trueket.backend.dispute.service.DisputeResultMailTransactionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "dispute.mail",
        name = "enabled",
        havingValue = "true"
)
@RequiredArgsConstructor
public class DisputeResultMailScheduler {

    private final DisputeRepository disputeRepository;
    private final DisputeResultMailTransactionService transactionService;
    private final DisputeNotificationService notificationService;

    // 1분마다 완료된 이의제기 확인
    @Scheduled(fixedDelay = 60000, initialDelay = 15000)
    public void sendCompletedResults() {

        var disputes = disputeRepository.findAllByStatus(
                DisputeStatus.DELIVERED
        );

        for (var dispute : disputes) {

            Long disputeId = dispute.getId();

            try {
                var optionalJob = transactionService.tryClaim(disputeId);

                if (optionalJob.isEmpty()) {
                    continue;
                }

                var job = optionalJob.get();

                try {
                    notificationService.sendResultEmail(
                            job.sellerEmail(),
                            job.receiptNo(),
                            job.maintainedCount(),
                            job.deletedCount(),
                            job.privateCount()
                    );

                    transactionService.markSent(
                            disputeId,
                            job.claimToken()
                    );

                    log.info(
                            "RP-08 결과 이메일 발송 완료: disputeId={}",
                            disputeId
                    );

                } catch (Exception e) {

                    log.error(
                            "RP-08 결과 이메일 발송 실패: disputeId={}",
                            disputeId,
                            e
                    );

                    transactionService.releaseForRetry(
                            disputeId,
                            job.claimToken()
                    );
                }

            } catch (Exception e) {
                log.error(
                        "RP-08 결과 메일 처리 오류: disputeId={}",
                        disputeId,
                        e
                );
            }
        }
    }
}