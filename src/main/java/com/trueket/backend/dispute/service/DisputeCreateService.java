
package com.trueket.backend.dispute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.dispute.dto.DisputeAiAnalysis;
import com.trueket.backend.dispute.dto.DisputeCreateResponse;
import com.trueket.backend.dispute.entity.DisputeItem;
import com.trueket.backend.dispute.entity.enums.DisputeReason;
import com.trueket.backend.dispute.repository.DisputeItemRepository;
import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.identifier.util.IdentifierNormalizer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeCreateService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DisputeTransactionService transactionService;
    private final DisputeAiService aiService;
    private final DisputeEvidenceStorageService storageService;
    private final DisputeNotificationService notificationService;
    private final DisputeItemRepository itemRepository;

    public DisputeCreateResponse create(
            String identifier,
            DisputeReason reason,
            String description,
            String sellerEmail,
            MultipartFile deliveryEvidence,
            MultipartFile transferEvidence
    ) {

        // 1. 사유별 필수 파일 검사
        if (reason != DisputeReason.NORMAL
                && reason != DisputeReason.REFUND) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "현재는 정상 거래와 환불 이의제기만 지원합니다."
            );
        }

        if (transferEvidence == null
                || transferEvidence.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "이체 증빙 이미지가 필요합니다."
            );
        }

        if (reason == DisputeReason.NORMAL
                && (deliveryEvidence == null
                || deliveryEvidence.isEmpty())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "표 전달 내역 이미지가 필요합니다."
            );
        }

        if (reason == DisputeReason.REFUND
                && deliveryEvidence != null
                && !deliveryEvidence.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "환불 이의제기에는 환불 증빙만 첨부해 주세요."
            );
        }

        // 2. 이미지를 AI 분석용 PNG로 변환
        byte[] transferImage = storageService.prepare(
                transferEvidence
        );

        byte[] deliveryImage = null;

        if (reason == DisputeReason.NORMAL) {
            deliveryImage = storageService.prepare(
                    deliveryEvidence
            );
        }

        // 3. CHECKING 접수 생성
        DisputeTransactionService.Created created =
                transactionService.createChecking(
                        identifier,
                        reason,
                        description,
                        sellerEmail
                );

        notifySafely(() ->
                notificationService.sendReceivedEmail(
                        sellerEmail,
                        created.receiptNo()
                )
        );

        // 업로드된 파일 경로 보관
        // 도중 실패하면 업로드된 파일을 정리
        List<String> uploadedKeys = new ArrayList<>();

        int deliveredCount;

        try {

            // 4. 입금 또는 환불 증빙 분석
            DisputeAiAnalysis transferAnalysis =
                    aiService.analyzeTransfer(
                            storageService.toAiImage(
                                    transferImage
                            )
                    );

            if (transferAnalysis == null) {
                throw new IllegalStateException(
                        "이체 증빙 AI 분석 결과가 없습니다."
                );
            }

            // 5. NORMAL일 때 표 전달 증빙도 분석
            DisputeAiAnalysis deliveryAnalysis = null;

            if (reason == DisputeReason.NORMAL) {
                deliveryAnalysis = aiService.analyzeDelivery(
                        storageService.toAiImage(
                                deliveryImage
                        )
                );

                if (deliveryAnalysis == null) {
                    throw new IllegalStateException(
                            "표 전달 AI 분석 결과가 없습니다."
                    );
                }
            }

            // 6. 사유별 검증
            boolean transferAccepted = isTransferAccepted(
                    reason,
                    transferAnalysis,
                    created.normalizedAccount()
            );

            boolean deliveryAccepted =
                    reason == DisputeReason.REFUND
                            || isDeliveryAccepted(deliveryAnalysis);

            boolean accepted =
                    transferAccepted && deliveryAccepted;

            // 계좌번호와 개인정보를 제외한 결과만 저장
            JsonNode checkResult = JSON.valueToTree(
                    Map.of(
                            "accepted", accepted,
                            "reason", reason.name(),
                            "transferAccepted", transferAccepted,
                            "deliveryAccepted", deliveryAccepted,
                            "transferDocType",
                            safe(transferAnalysis.docType()),
                            "transferReadable",
                            transferAnalysis.readable(),
                            "transferCompleted",
                            transferAnalysis.completed()
                    )
            );

            // 7. 검증 실패: REJECTED
            if (!accepted) {

                transactionService.reject(
                        created.disputeId(),
                        checkResult
                );

                notifySafely(() ->
                        notificationService.sendRejectedEmail(
                                sellerEmail,
                                created.receiptNo()
                        )
                );

                return new DisputeCreateResponse(
                        created.disputeId(),
                        created.receiptNo(),
                        "REJECTED",
                        0,
                        "증빙 확인 결과가 이의제기 조건과 일치하지 않습니다."
                );
            }

            // 8. 통과한 이미지 개인정보 마스킹
            byte[] maskedTransfer = storageService.mask(
                    transferImage,
                    transferAnalysis
            );

            byte[] maskedDelivery = null;

            if (reason == DisputeReason.NORMAL) {
                maskedDelivery = storageService.mask(
                        deliveryImage,
                        deliveryAnalysis
                );
            }

            // 9. 이미지 업로드
            // NORMAL: 전달 캡처 1개 + 이체 내역 1개
            // REFUND: 환불 이체 내역 1개

            if (maskedDelivery != null) {
                String deliveryKey = storageService.upload(
                        created.disputeId(),
                        maskedDelivery
                );
                uploadedKeys.add(deliveryKey);
            }

            String transferKey = storageService.upload(
                    created.disputeId(),
                    maskedTransfer
            );
            uploadedKeys.add(transferKey);

            // 10. DisputeItem 생성 및 DELIVERED 처리
            deliveredCount = transactionService.deliver(
                    created.disputeId(),
                    checkResult,
                    List.copyOf(uploadedKeys)
            );

        } catch (RuntimeException e) {

            log.error(
                    "RP-08 접수 처리 장애: disputeId={}",
                    created.disputeId(),
                    e
            );

            // DB 확정 전에 실패했다면 업로드된 파일 정리
            for (String key : uploadedKeys) {
                try {
                    storageService.delete(key);
                } catch (Exception cleanupError) {
                    log.error(
                            "증빙 이미지 정리 실패: disputeId={}",
                            created.disputeId(),
                            cleanupError
                    );
                }
            }

            // CHECKING 상태인 접수만 FAILED로 변경
            try {
                transactionService.failChecking(
                        created.disputeId()
                );
            } catch (Exception recoveryError) {
                log.error(
                        "접수 실패 상태 저장 오류: disputeId={}",
                        created.disputeId(),
                        recoveryError
                );
            }

            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "증빙 확인 중 오류가 발생했습니다. "
                            + "접수번호를 보관하고 다시 신청해 주세요."
            );
        }

        // 11. DB 확정 이후 이메일 안내
        // 메일 실패는 정상 접수 결과에 영향을 주지 않음

        notifySafely(() -> {

            var items = itemRepository.findAllByDispute_Id(
                    created.disputeId()
            );

            OffsetDateTime deadline = items.stream()
                    .map(DisputeItem::getDeadline)
                    .min(OffsetDateTime::compareTo)
                    .orElseThrow();

            notificationService.sendDeliveredEmail(
                    sellerEmail,
                    created.receiptNo(),
                    deliveredCount,
                    deadline
            );
        });

        notifySafely(() ->
                itemRepository.findAllByDispute_Id(
                        created.disputeId()
                ).forEach(item ->
                        notifySafely(() ->
                                notificationService.sendReporterDisputeEmail(
                                        item.getReport().getId(),
                                        created.disputeId()
                                )
                        )
                )
        );

        // 12. 최종 결과
        return new DisputeCreateResponse(
                created.disputeId(),
                created.receiptNo(),
                "DELIVERED",
                deliveredCount,
                "증빙 검증을 통과하여 이의제기가 전달되었습니다."
        );
    }

    // 입금/환불 증빙 검증
    private boolean isTransferAccepted(
            DisputeReason reason,
            DisputeAiAnalysis analysis,
            String targetAccount
    ) {

        if (analysis == null
                || !analysis.readable()
                || !analysis.completed()
                || !"transfer_receipt".equals(
                analysis.docType()
        )) {
            return false;
        }

        String bank;
        String account;

        if (reason == DisputeReason.NORMAL) {

            // NORMAL: 대상 계좌로 돈이 들어온 기록
            bank = analysis.receiverBank();
            account = analysis.receiverAccount();

        } else if (reason == DisputeReason.REFUND) {

            // REFUND: 대상 계좌에서 돈을 보낸 기록
            bank = analysis.senderBank();
            account = analysis.senderAccount();

        } else {
            return false;
        }

        if (bank == null
                || bank.isBlank()
                || account == null
                || account.isBlank()) {
            return false;
        }

        try {
            var normalized = IdentifierNormalizer.normalize(
                    bank + " " + account
            );

            return normalized.type() == IdentifierType.ACCOUNT
                    && normalized.value().equals(targetAccount);

        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // 표 전달 증빙 검증
    private boolean isDeliveryAccepted(
            DisputeAiAnalysis analysis
    ) {
        return analysis != null
                && analysis.readable()
                && "ticket_delivery".equals(analysis.docType())
                && analysis.delivered();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void notifySafely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error(
                    "이의제기 이메일 처리 실패",
                    e
            );
        }
    }
}
