
package com.trueket.backend.dispute.service;

import com.trueket.backend.dispute.entity.Dispute;
import com.trueket.backend.dispute.repository.DisputeRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DisputeResultMailTransactionService {

    private final EntityManager entityManager;
    private final DisputeRepository disputeRepository;

    public record MailJob(
            Long disputeId,
            String receiptNo,
            String sellerEmail,
            String claimToken,
            int maintainedCount,
            int deletedCount,
            int privateCount
    ) {}

    @Transactional
    public Optional<MailJob> tryClaim(Long disputeId) {

        String token = UUID.randomUUID().toString();

        // 결과가 확정됐고 다른 작업자가 발송 중이지 않은 경우만 선점
        int claimed = entityManager.createNativeQuery("""
                UPDATE public.disputes d
                SET result_mail_state = 'SENDING',
                    result_mail_claimed_at = NOW(),
                    result_mail_claim_token = ?2
                WHERE d.id = ?1
                  AND d.status = 'DELIVERED'

                  AND (
                      (
                          d.result_mail_state = 'PENDING'
                          AND (
                              d.result_mail_claimed_at IS NULL
                              OR d.result_mail_claimed_at
                                  < NOW() - INTERVAL '1 minute'
                          )
                      )
                      OR (
                          d.result_mail_state = 'SENDING'
                          AND d.result_mail_claimed_at
                              < NOW() - INTERVAL '5 minutes'
                      )
                  )

                  -- 처리 대기 중인 기록이 없어야 함
                  AND NOT EXISTS (
                      SELECT 1
                      FROM public.dispute_items di
                      WHERE di.dispute_id = d.id
                        AND di.status IN ('RESPONDING', 'REVIEWING')
                  )

                  -- 원래 전달 대상 수와 결과 이력 수가 같아야 함
                  AND (
                      (
                          SELECT COUNT(*)
                          FROM public.dispute_items di
                          WHERE di.dispute_id = d.id
                      )
                      +
                      (
                          SELECT COUNT(*)
                          FROM public.dispute_deleted_records dr
                          WHERE dr.dispute_id = d.id
                      )
                  ) = jsonb_array_length(d.target_report_ids)

                  AND jsonb_array_length(d.target_report_ids) > 0
                """)
                .setParameter(1, disputeId)
                .setParameter(2, token)
                .executeUpdate();

        if (claimed == 0) {
            return Optional.empty();
        }

        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow();

        Object[] counts = (Object[]) entityManager
                .createNativeQuery("""
                        SELECT
                            (
                                SELECT COUNT(*)
                                FROM public.dispute_items
                                WHERE dispute_id = ?1
                                  AND status = 'MAINTAINED'
                            ),
                            (
                                SELECT COUNT(*)
                                FROM public.dispute_deleted_records
                                WHERE dispute_id = ?1
                            ),
                            (
                                SELECT COUNT(*)
                                FROM public.dispute_items
                                WHERE dispute_id = ?1
                                  AND status = 'PRIVATE'
                            )
                        """)
                .setParameter(1, disputeId)
                .getSingleResult();

        int maintained = ((Number) counts[0]).intValue();
        int deleted = ((Number) counts[1]).intValue();
        int privateCount = ((Number) counts[2]).intValue();

        return Optional.of(new MailJob(
                disputeId,
                dispute.getReceiptNo(),
                dispute.getSellerEmail(),
                token,
                maintained,
                deleted,
                privateCount
        ));
    }

    @Transactional
    public void markSent(Long disputeId, String token) {

        entityManager.createNativeQuery("""
                UPDATE public.disputes
                SET result_mail_state = 'SENT',
                    result_mail_sent_at = NOW(),
                    result_mail_claim_token = NULL
                WHERE id = ?1
                  AND result_mail_state = 'SENDING'
                  AND result_mail_claim_token = ?2
                """)
                .setParameter(1, disputeId)
                .setParameter(2, token)
                .executeUpdate();
    }

    @Transactional
    public void releaseForRetry(Long disputeId, String token) {

        entityManager.createNativeQuery("""
                UPDATE public.disputes
                SET result_mail_state = 'PENDING',
                    result_mail_claimed_at = NOW(),
                    result_mail_claim_token = NULL
                WHERE id = ?1
                  AND result_mail_state = 'SENDING'
                  AND result_mail_claim_token = ?2
                """)
                .setParameter(1, disputeId)
                .setParameter(2, token)
                .executeUpdate();
    }
}
