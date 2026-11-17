package com.trueket.backend.dispute.service;

import com.trueket.backend.dispute.dto.DisputeAvailabilityResponse;
import com.trueket.backend.dispute.entity.Dispute;
import com.trueket.backend.dispute.entity.enums.DisputeItemStatus;
import com.trueket.backend.dispute.entity.enums.DisputeStatus;
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

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DisputeAvailabilityService {

    private final IdentifierRepository identifierRepository;
    private final IdentifierHashService identifierHashService;
    private final ReportIdentifierRepository reportIdentifierRepository;
    private final DisputeRepository disputeRepository;
    private final DisputeItemRepository disputeItemRepository;

    public DisputeAvailabilityResponse check(String q) {

        // 1. 계좌번호 / 전화번호 / SNS 정규화
        IdentifierNormalizer.NormalizedIdentifier normalized;

        try {
            normalized = IdentifierNormalizer.normalize(q);
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    e.getMessage()
            );
        }

        // 2. 해시 생성
        String hash = identifierHashService.hash(
                normalized.type(),
                normalized.value()
        );

        // 3. 기존 식별자 조회
        Optional<Identifier> optionalIdentifier =
                identifierRepository.findByTypeAndValueHash(
                        normalized.type(),
                        hash
                );

        if (optionalIdentifier.isEmpty()) {
            return unavailable();
        }

        Long identifierId = optionalIdentifier.get().getId();

        // 4. AI-04 확인 중인 이의제기 검사
        if (disputeRepository.existsByIdentifier_IdAndStatus(
                identifierId,
                DisputeStatus.CHECKING
        )) {
            return processing();
        }

        // 5. 게시자가 응답 중이거나 운영팀이 검토 중인지 확인
        boolean processingItems =
                disputeItemRepository.existsProcessingItem(
                        identifierId,
                        List.of(
                                DisputeItemStatus.RESPONDING,
                                DisputeItemStatus.REVIEWING
                        )
                );

        if (processingItems) {
            return processing();
        }

        // 6. 현재 공개된 최근 1년 신고 조회
        OffsetDateTime since = OffsetDateTime.now().minusYears(1);

        List<ReportIdentifier> reportIdentifiers =
                reportIdentifierRepository.findActiveReportsByIdentifier(
                        identifierId,
                        List.of(ReportStatus.PUBLISHED),
                        since
                );

        // 7. 이전에 이의를 받지 않은 신고만 선택
        List<Report> newReports = reportIdentifiers.stream()
                .map(ReportIdentifier::getReport)
                .distinct()
                .filter(report ->
                        !disputeItemRepository.existsByReportAndIdentifier(
                                report.getId(),
                                identifierId
                        )
                )
                .toList();

        if (newReports.isEmpty()) {
            return unavailable();
        }

        // 8. 과거에 게시자에게 전달된 이의제기가 있었는지 확인
        boolean hasPreviousDelivery =
                disputeRepository
                        .findAllByIdentifier_IdOrderByCreatedAtDesc(identifierId)
                        .stream()
                        .map(Dispute::getStatus)
                        .anyMatch(status ->
                                status == DisputeStatus.DELIVERED
                        );

        if (hasPreviousDelivery) {
            return new DisputeAvailabilityResponse(
                    "NEW_REPORTS_ONLY",
                    true,
                    newReports.size()
            );
        }

        return new DisputeAvailabilityResponse(
                "AVAILABLE",
                true,
                newReports.size()
        );
    }

    private DisputeAvailabilityResponse processing() {
        return new DisputeAvailabilityResponse(
                "PROCESSING",
                false,
                0
        );
    }

    private DisputeAvailabilityResponse unavailable() {
        return new DisputeAvailabilityResponse(
                "NO_NEW_REPORTS",
                false,
                0
        );
    }
}