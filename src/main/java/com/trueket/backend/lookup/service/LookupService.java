
package com.trueket.backend.lookup.service;

import com.trueket.backend.global.exception.BusinessException;
import com.trueket.backend.global.exception.ErrorCode;
import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.identifier.repository.IdentifierRepository;
import com.trueket.backend.lookup.dto.LookupDetailResponse;
import com.trueket.backend.lookup.dto.LookupResponse;
import com.trueket.backend.lookup.dto.LookupTimelineResponse;
import com.trueket.backend.lookup.dto.RelatedIdentifierResponse;
import com.trueket.backend.lookup.util.IdentifierHashService;
import com.trueket.backend.lookup.util.IdentifierNormalizer;
import com.trueket.backend.report.entity.Report;
import com.trueket.backend.report.entity.ReportIdentifier;
import com.trueket.backend.report.entity.enums.ReportKind;
import com.trueket.backend.report.entity.enums.ReportStatus;
import com.trueket.backend.report.repository.ReportIdentifierRepository;
import com.trueket.backend.lookup.dto.LookupCompareResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LookupService {

    private final IdentifierRepository identifierRepository;
    private final ReportIdentifierRepository reportIdentifierRepository;
    private final IdentifierHashService identifierHashService;

    private static final List<ReportStatus> ACTIVE_STATUSES = List.of(
            ReportStatus.PUBLISHED,
            ReportStatus.RESPONDING,
            ReportStatus.REVIEWING
    );

    /**
     * RP-01. 신고 리스트 조회
     *
     * @param query 검색창에 입력한 원본 값
     * @return 식별자 정보 및 신고 위험도
     */
    public LookupResponse lookup(String query) {

        // 1. 입력값 유형 판별 및 정규화
        IdentifierNormalizer.NormalizedIdentifier normalized =
                IdentifierNormalizer.normalize(query);

        // 2. 유형과 정규화된 값으로 해시 생성
        String valueHash = identifierHashService.hash(
                normalized.type(),
                normalized.value()
        );

        // 3. DB에서 정확히 일치하는 식별자 검색
        Identifier identifier = identifierRepository
                .findByTypeAndValueHash(
                        normalized.type(),
                        valueHash
                )
                .orElse(null);

        // 4. 검색 결과가 없으면 SAFE 반환
        if (identifier == null) {
            return new LookupResponse(
                    null,
                    normalized.type(),
                    null,
                    "SAFE",
                    0,
                    0
            );
        }

        // 5. 최근 1년간 유효한 신고만 조회
        OffsetDateTime since = OffsetDateTime.now().minusYears(1);

        List<ReportIdentifier> records =
                reportIdentifierRepository.findActiveReportsByIdentifier(
                        identifier.getId(),
                        ACTIVE_STATUSES,
                        since
                );

        // 6. 피해 신고자 수 (동일 휴대폰 중복 제거)
        Set<String> fraudReporters = records.stream()
                .filter(ri ->
                        ri.getReport().getKind() == ReportKind.FRAUD
                )
                .map(ri ->
                        ri.getReport().getReporterHash()
                )
                .collect(Collectors.toSet());

        // 7. 의심 제보자 수 (동일 휴대폰 중복 제거)
        Set<String> suspicionReporters = records.stream()
                .filter(ri ->
                        ri.getReport().getKind() == ReportKind.SUSPICION
                )
                .map(ri ->
                        ri.getReport().getReporterHash()
                )
                .collect(Collectors.toSet());

        long fraudCount = fraudReporters.size();
        long suspicionCount = suspicionReporters.size();

        // 8. 신고 리스트 기준 위험도 계산
        String riskLevel = LookupRiskCalculator.calculate(
                fraudCount,
                suspicionCount
        );

        // 9. 조회 결과 반환
        return new LookupResponse(
                identifier.getId(),
                identifier.getType(),
                normalized.value(),
                riskLevel,
                fraudCount,
                suspicionCount
        );
    }

    public LookupDetailResponse getLookupDetail(Long identifierId) {

        Identifier identifier = identifierRepository.findById(identifierId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.IDENTIFIER_NOT_FOUND
                ));

        List<ReportIdentifier> records =
                reportIdentifierRepository.findActiveReportsByIdentifier(
                        identifierId,
                        ACTIVE_STATUSES,
                        OffsetDateTime.now().minusYears(1)
                );

        Set<String> fraudReporters = new HashSet<>();
        Set<String> suspicionReporters = new HashSet<>();

        for (ReportIdentifier record : records) {
            Report report = record.getReport();

            if (report.getKind() == ReportKind.FRAUD) {
                fraudReporters.add(report.getReporterHash());
            } else if (report.getKind() == ReportKind.SUSPICION) {
                suspicionReporters.add(report.getReporterHash());
            }
        }

        long fraudCount = fraudReporters.size();
        long suspicionCount = suspicionReporters.size();

        String riskLevel = LookupRiskCalculator.calculate(
                fraudCount,
                suspicionCount
        );


        List<LookupTimelineResponse> timeline = records.stream()
                .map(ReportIdentifier::getReport)
                .collect(Collectors.toMap(
                        Report::getId,
                        report -> report,
                        (existing, duplicate) -> existing
                ))
                .values()
                .stream()
                .sorted(
                        Comparator.comparing(
                                        Report::getCreatedAt
                                ).reversed()
                                .thenComparing(
                                        Report::getId,
                                        Comparator.reverseOrder()
                                )
                )
                .map(report -> new LookupTimelineResponse(
                        report.getCreatedAt().toLocalDate(),
                        report.getKind()
                ))
                .toList();


        List<RelatedIdentifierResponse> relatedIdentifiers =
                getRelatedIdentifiers(identifierId, records);

        return new LookupDetailResponse(
                identifier.getId(),
                identifier.getType(),
                identifier.getValueMasked(),
                riskLevel,
                fraudCount,
                suspicionCount,
                relatedIdentifiers,
                timeline
        );
    }

    private List<RelatedIdentifierResponse> getRelatedIdentifiers(
            Long identifierId,
            List<ReportIdentifier> records
    ) {
        if (records.isEmpty()) {
            return List.of();
        }

        List<Long> reportIds = records.stream()
                .map(record -> record.getReport().getId())
                .distinct()
                .toList();

        List<ReportIdentifier> relatedRecords =
                reportIdentifierRepository
                        .findVisibleIdentifiersByReportIds(reportIds);

        Map<Long, Set<String>> reporterHashes = new HashMap<>();
        Map<Long, Identifier> identifiers = new HashMap<>();

        for (ReportIdentifier record : relatedRecords) {
            Identifier related = record.getIdentifier();

            if (related.getId().equals(identifierId)) {
                continue;
            }

            Long relatedId = related.getId();

            reporterHashes.computeIfAbsent(
                    relatedId,
                    key -> new HashSet<>()
            ).add(record.getReport().getReporterHash());

            identifiers.putIfAbsent(relatedId, related);
        }

        return reporterHashes.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= 2)
                .map(entry -> {
                    Identifier related = identifiers.get(entry.getKey());

                    return new RelatedIdentifierResponse(
                            related.getId(),
                            related.getType(),
                            related.getValueMasked()
                    );
                })
                .sorted(Comparator.comparing(
                        RelatedIdentifierResponse::identifierId
                ))
                .toList();
    }


    /**
     * RP-03. 식별자 전체 값 비교
     *
     * @param identifierId 비교 대상 식별자 ID
     * @param inputValue 사용자가 입력한 전체 값
     * @return 일치 여부 및 일치할 경우 전체 값
     */
    public LookupCompareResponse compare(
            Long identifierId,
            String inputValue
    ) {
        // 1. 비교 대상 식별자 조회
        Identifier identifier = identifierRepository
                .findById(identifierId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.IDENTIFIER_NOT_FOUND
                ));

        // 2. 사용자 입력값 정규화
        IdentifierNormalizer.NormalizedIdentifier normalized =
                IdentifierNormalizer.normalize(inputValue);

        // 3. 식별자 유형이 다르면 불일치
        if (identifier.getType() != normalized.type()) {
            return new LookupCompareResponse(false, null);
        }

        // 4. 정규화된 값으로 HMAC 해시 생성
        String inputHash = identifierHashService.hash(
                normalized.type(),
                normalized.value()
        );

        // 5. DB의 해시와 비교
        boolean matched = java.security.MessageDigest.isEqual(
                identifier.getValueHash().getBytes(
                        java.nio.charset.StandardCharsets.UTF_8
                ),
                inputHash.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8
                )
        );

        // 6. 불일치하면 전체 값 노출 금지
        if (!matched) {
            return new LookupCompareResponse(false, null);
        }

        // 7. 일치하면 검증된 정규화 입력값 반환
        return new LookupCompareResponse(
                true,
                normalized.value()
        );
    }

}
