
package com.trueket.backend.dispute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.dispute.entity.Dispute;
import com.trueket.backend.dispute.entity.DisputeItem;
import com.trueket.backend.dispute.entity.enums.*;
import com.trueket.backend.dispute.repository.DisputeItemRepository;
import com.trueket.backend.dispute.repository.DisputeRepository;
import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.identifier.repository.IdentifierRepository;
import com.trueket.backend.identifier.util.IdentifierHashService;
import com.trueket.backend.identifier.util.IdentifierNormalizer;
import com.trueket.backend.report.entity.Report;
import com.trueket.backend.report.entity.ReportIdentifier;
import com.trueket.backend.report.entity.enums.ReportStatus;
import com.trueket.backend.report.repository.ReportIdentifierRepository;
import com.trueket.backend.report.repository.ReportRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DisputeTransactionService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final IdentifierRepository identifierRepository;
    private final IdentifierHashService hashService;
    private final ReportIdentifierRepository reportIdentifierRepository;
    private final ReportRepository reportRepository;
    private final DisputeRepository disputeRepository;
    private final DisputeItemRepository itemRepository;
    private final EntityManager entityManager;

    public record Created(
            Long disputeId,
            String receiptNo,
            String normalizedAccount,
            int targetCount
    ) {}

    @Transactional
    public Created createChecking(
            String identifierValue,
            DisputeReason reason,
            String description,
            String sellerEmail
    ) {
        IdentifierNormalizer.NormalizedIdentifier normalized;

        try {
            normalized = IdentifierNormalizer.normalize(identifierValue);
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }

        if (normalized.type()
                != com.trueket.backend.identifier.entity.enums.IdentifierType.ACCOUNT) {
            throw badRequest(
                    "현재는 은행 계좌 이의제기만 지원합니다."
            );
        }

        if (reason != DisputeReason.NORMAL
                && reason != DisputeReason.REFUND) {
            throw badRequest(
                    "현재는 정상 거래와 환불 이의제기만 지원합니다."
            );
        }

        String hash = hashService.hash(
                normalized.type(),
                normalized.value()
        );

        Identifier found = identifierRepository
                .findByTypeAndValueHash(normalized.type(), hash)
                .orElseThrow(() ->
                        badRequest("이의제기 대상 기록이 없습니다.")
                );

        // 같은 식별자에 대한 동시 접수 직렬화
        Identifier identifier = identifierRepository
                .findByIdForUpdate(found.getId())
                .orElseThrow(() ->
                        badRequest("이의제기 대상이 없습니다.")
                );

        Long identifierId = identifier.getId();

        if (disputeRepository.existsByIdentifier_IdAndStatus(
                identifierId, DisputeStatus.CHECKING
        )) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "이미 증빙을 확인 중인 이의제기가 있습니다."
            );
        }

        if (itemRepository.existsProcessingItem(
                identifierId,
                List.of(
                        DisputeItemStatus.RESPONDING,
                        DisputeItemStatus.REVIEWING
                )
        )) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "현재 처리 중인 이의제기가 있습니다."
            );
        }

        List<Long> reportIds = reportIdentifierRepository
                .findActiveReportsByIdentifier(
                        identifierId,
                        List.of(ReportStatus.PUBLISHED),
                        OffsetDateTime.now().minusYears(1)
                )
                .stream()
                .map(ReportIdentifier::getReport)
                .map(Report::getId)
                .distinct()
                .filter(id ->
                        !itemRepository.existsByReportAndIdentifier(
                                id, identifierId
                        )
                )
                .toList();

        if (reportIds.isEmpty()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "새롭게 이의제기할 기록이 없습니다."
            );
        }

        Number sequence = (Number) entityManager
                .createNativeQuery(
                        "SELECT nextval('public.dispute_receipt_seq')"
                )
                .getSingleResult();

        String month = OffsetDateTime.now(
                ZoneId.of("Asia/Seoul")
        ).format(DateTimeFormatter.ofPattern("yyMM"));

        String receiptNo = "TK-" + month + "-"
                + String.format("%06d", sequence.longValue());

        JsonNode targetIds = JSON.valueToTree(reportIds);

        Dispute dispute = Dispute.create(
                receiptNo,
                identifier,
                reason,
                description,
                sellerEmail,
                targetIds
        );

        disputeRepository.saveAndFlush(dispute);

        return new Created(
                dispute.getId(),
                receiptNo,
                normalized.value(),
                reportIds.size()
        );
    }

    @Transactional
    public void reject(Long disputeId, JsonNode analysis) {
        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> badRequest("접수 내역이 없습니다."));

        dispute.reject(analysis);
    }

    @Transactional
    public int deliver(
            Long disputeId,
            JsonNode analysis,
            List<String> imageKeys
    ) {
        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> badRequest("접수 내역이 없습니다."));

        if (dispute.getStatus() != DisputeStatus.CHECKING) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "이미 처리된 이의제기입니다."
            );
        }

        JsonNode targetIds = dispute.getTargetReportIds();

        if (targetIds == null || !targetIds.isArray()
                || targetIds.isEmpty()) {
            throw new IllegalStateException(
                    "이의제기 대상 신고 목록이 비어 있습니다."
            );
        }

        List<Long> ids = new java.util.ArrayList<>();
        for (JsonNode node : targetIds) {
            ids.add(node.longValue());
        }

        List<Report> reports = reportRepository.findAllById(ids);

        if (reports.size() != ids.size()) {
            throw new IllegalStateException(
                    "이의제기 대상 신고 중 삭제된 신고가 있습니다."
            );
        }

        int deliveredCount = 0;

        for (Report report : reports) {
            if (itemRepository.existsByReportAndIdentifier(
                    report.getId(),
                    dispute.getIdentifier().getId()
            )) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "이미 이의제기를 받은 신고입니다."
                );
            }

            if (report.getStatus() != ReportStatus.PUBLISHED) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "이의제기 대상 신고 상태가 변경되었습니다."
                );
            }

            DisputeItem item = DisputeItem.create(dispute, report);
            itemRepository.save(item);

            report.changeStatus(ReportStatus.RESPONDING);
            deliveredCount++;
        }

        dispute.setProofImages(
                JSON.valueToTree(imageKeys)
        );

        dispute.deliver(analysis);
        itemRepository.flush();

        return deliveredCount;
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    @Transactional
    public void failChecking(Long disputeId) {
        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() ->
                        badRequest("접수 내역이 없습니다.")
                );

        dispute.fail();
        disputeRepository.flush();
    }
}
