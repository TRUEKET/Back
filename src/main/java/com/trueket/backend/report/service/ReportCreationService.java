
package com.trueket.backend.report.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.trueket.backend.analyze.dto.AnalysisReportSource;
import com.trueket.backend.analyze.service.AnalysisReportSourceService;
import com.trueket.backend.global.exception.BusinessException;
import com.trueket.backend.global.exception.ErrorCode;
import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.identifier.repository.IdentifierRepository;
import com.trueket.backend.identifier.service.IdentifierEncryptionService;
import com.trueket.backend.identifier.util.IdentifierMasker;
import com.trueket.backend.identifier.util.IdentifierHashService;
import com.trueket.backend.identifier.util.IdentifierNormalizer;
import com.trueket.backend.report.dto.ReportCreateRequest;
import com.trueket.backend.report.dto.ReportCreateResponse;
import com.trueket.backend.report.entity.Report;
import com.trueket.backend.report.entity.ReportIdentifier;
import com.trueket.backend.report.entity.enums.ReportKind;
import com.trueket.backend.report.repository.ReportIdentifierRepository;
import com.trueket.backend.report.repository.ReportRepository;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.report.entity.Evidence;
import com.trueket.backend.report.entity.PendingReportEvidence;
import com.trueket.backend.report.entity.enums.EvidenceKind;
import com.trueket.backend.report.repository.EvidenceRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trueket.backend.analyze.dto.ReportEvidenceDetail;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportCreationService {

    private final EntityManager entityManager;
    private final ReportRepository reportRepository;
    private final ReportIdentifierRepository reportIdentifierRepository;
    private final IdentifierRepository identifierRepository;
    private final IdentifierHashService identifierHashService;
    private final IdentifierEncryptionService encryptionService;
    private final PendingReportEvidenceService pendingReportEvidenceService;
    private final EvidenceRepository evidenceRepository;
    private final SupabaseReportEvidenceStore evidenceStore;

    // JD 판독 결과 조회 서비스
    private final AnalysisReportSourceService analysisReportSourceService;

    @Transactional
    public ReportCreateResponse create(
            Long authenticatedUserId,
            ReportCreateRequest request,
            HttpSession session
    ) {
        // 1. JD 판독 결과 조회 및 소유권 검증
        AnalysisReportSource source =
                analysisReportSourceService.load(
                        request.analysisId(),
                        session
                );

        if (source.userId() != authenticatedUserId.longValue()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // 2. 신고 가능 여부 검증
        validateAnalysis(source, request.kind());

        // 3. 사용자 조회 및 중복 신고 방지를 위한 잠금
        User user = entityManager.find(
                User.class,
                authenticatedUserId,
                LockModeType.PESSIMISTIC_WRITE
        );

        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        if (!"ACTIVE".equals(user.getStatus())
                || user.getPhoneVerifiedAt() == null
                || user.getPhoneHash() == null
                || user.getPhoneHash().isBlank()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }


        // 4. JD 식별자를 종류별로 정규화
        List<IdentifierNormalizer.NormalizedIdentifier> normalized =
                new ArrayList<>();

        // 계좌번호
        for (String account : source.identifiers().accounts()) {
            addNormalizedIdentifier(
                    normalized,
                    account,
                    IdentifierType.ACCOUNT
            );
        }

        // 전화번호
        for (String phone : source.identifiers().phones()) {
            addNormalizedIdentifier(
                    normalized,
                    phone,
                    IdentifierType.PHONE
            );
        }

        // X 계정
        for (String xAccount : source.identifiers().xAccounts()) {
            String value = xAccount == null
                    ? null
                    : xAccount.trim();

            if (value != null && !value.startsWith("@")) {
                value = "@" + value;
            }

            addNormalizedIdentifier(
                    normalized,
                    value,
                    IdentifierType.SNS
            );
        }

        // 5. 정규화된 식별자 중복 제거
        Map<String, IdentifierNormalizer.NormalizedIdentifier> unique =
                new LinkedHashMap<>();

        for (var item : normalized) {
            String hash = identifierHashService.hash(
                    item.type(),
                    item.value()
            );

            unique.putIfAbsent(
                    item.type().name() + ":" + hash,
                    item
            );
        }

        normalized = new ArrayList<>(unique.values());

        if (normalized.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST
            );
        }


        // 6. 동일 신고자의 동일 계좌 피해 신고 중복 확인
        if (request.kind() == ReportKind.FRAUD) {
            for (var item : normalized) {
                if (item.type() != IdentifierType.ACCOUNT) {
                    continue;
                }

                String hash = identifierHashService.hash(
                        item.type(),
                        item.value()
                );

                boolean duplicated =
                        reportRepository.existsReportByReporterAndIdentifier(
                                user.getPhoneHash(),
                                IdentifierType.ACCOUNT,
                                hash,
                                ReportKind.FRAUD
                        );

                if (duplicated) {
                    throw new BusinessException(
                            ErrorCode.DUPLICATE_REPORT
                    );
                }
            }
        }


// 7-1. JD 근거 말풍선 저장 (기존 호환성 유지)
        ArrayNode evidenceQuotesJson =
                JsonNodeFactory.instance.arrayNode();

        for (var evidence : source.evidence()) {
            evidenceQuotesJson.add(evidence.quote());
        }

// 7-2. JD 주요 대화 상세 정보 저장
        ArrayNode evidenceDetailsJson =
                JsonNodeFactory.instance.arrayNode();

        for (ReportEvidenceDetail detail : source.evidenceDetails()) {

            ObjectNode detailNode =
                    evidenceDetailsJson.addObject();

            detailNode.put("imageIndex", detail.imageIndex());
            detailNode.put("signalId", detail.signalId());
            detailNode.put("quote", detail.quote());
            detailNode.put("severity", detail.severity().name());
            detailNode.put("action", detail.action());
            detailNode.put("reason", detail.reason());
            detailNode.put("informational", detail.informational());

            // 실제 피해 사례 및 기사 정보
            ArrayNode casesNode = detailNode.putArray("cases");

            for (var fraudCase : detail.cases()) {

                ObjectNode caseNode = casesNode.addObject();

                caseNode.put("signalId", fraudCase.signalId());
                caseNode.put("summary", fraudCase.summary());
                caseNode.put("source", fraudCase.source());
                caseNode.put("publishedAt", fraudCase.publishedAt());
                caseNode.put("url", fraudCase.url());
            }
        }


        // 8. 참고 링크 저장
        List<String> links = request.referenceLinks() == null
                ? List.of()
                : request.referenceLinks();

        ArrayNode referenceLinksJson =
                JsonNodeFactory.instance.arrayNode();

        for (String link : links) {
            referenceLinksJson.add(link);
        }
        
        // 9. 신고 생성
        Report report = Report.create(
                user,
                user.getPhoneHash(),
                request.kind(),
                source.tradeType(),
                evidenceQuotesJson,
                evidenceDetailsJson,
                referenceLinksJson,
                request.sentDate()
        );

        reportRepository.saveAndFlush(report);


        // 10. 식별자 조회 또는 생성
        List<Long> identifierIds = new ArrayList<>();

        for (var item : normalized) {
            String valueHash = identifierHashService.hash(
                    item.type(),
                    item.value()
            );

            Identifier identifier = identifierRepository
                    .findByTypeAndValueHash(
                            item.type(),
                            valueHash
                    )
                    .orElseGet(() -> identifierRepository.saveAndFlush(
                            Identifier.create(
                                    item.type(),
                                    valueHash,
                                    IdentifierMasker.mask(
                                            item.type(),
                                            item.value()
                                    ),
                                    encryptionService.encrypt(item.value())
                            )
                    ));

            // 11. 신고-식별자 연결
            reportIdentifierRepository.save(
                    ReportIdentifier.create(
                            report,
                            identifier
                    )
            );

            identifierIds.add(identifier.getId());
        }

        reportIdentifierRepository.flush();
        // 12. 증빙 사진 연결
        if (request.evidenceToken() != null) {

            // JD 판독 결과에서 정규화된 계좌번호만 추출
            List<String> normalizedAccounts = normalized.stream()
                    .filter(item -> item.type() == IdentifierType.ACCOUNT)
                    .map(IdentifierNormalizer.NormalizedIdentifier::value)
                    .toList();

            // 토큰, 사용자, 판독 ID, 계좌 일치 여부 검증
            PendingReportEvidence pending =
                    pendingReportEvidenceService.consume(
                            request.evidenceToken(),
                            authenticatedUserId,
                            request.analysisId(),
                            normalizedAccounts
                    );

            // 최종 증빙 경로 생성
            String reportStorageKey =
                    evidenceStore.createReportKey(authenticatedUserId);

            // DB 커밋 결과에 따른 Storage 정리 예약
            registerEvidenceStorageCleanup(
                    pending.getStorageKey(),
                    reportStorageKey
            );

            // Supabase Storage에 최종 증빙 복사
            evidenceStore.copyPendingToReport(
                    pending.getStorageKey(),
                    reportStorageKey
            );


            OffsetDateTime createdAt = OffsetDateTime.now();

            Evidence evidence = Evidence.create(
                    report,
                    EvidenceKind.NUMBER_FRAGMENT,
                    reportStorageKey,
                    pending.getAccountHash(),
                    ReportEvidenceRetentionPolicy.finalExpiresAt(createdAt)
            );

            evidenceRepository.saveAndFlush(evidence);

        }

        // 12. 응답
        return new ReportCreateResponse(
                report.getId(),
                report.getKind(),
                report.getStatus(),
                List.copyOf(identifierIds)
        );
    }


    private void validateAnalysis(
            AnalysisReportSource source,
            ReportKind kind
    ) {
        // 1. 필수 판독 데이터 검증
        if (source == null
                || kind == null
                || source.identifiers() == null
                || source.evidence() == null) {
            throw new BusinessException(
                    ErrorCode.REPORT_NOT_ALLOWED
            );
        }

        // 2. 신고 등록이 허용되지 않은 판독 결과
        if (!source.showReportButton()) {
            throw new BusinessException(
                    ErrorCode.REPORT_NOT_ALLOWED
            );
        }

        // 3. 의심 제보는 대화 자체에 의심 신호가 있어야 함
        if (kind == ReportKind.SUSPICION
                && !source.showSuspicionReport()) {
            throw new BusinessException(
                    ErrorCode.REPORT_NOT_ALLOWED
            );
        }
    }



    private void addNormalizedIdentifier(
            List<IdentifierNormalizer.NormalizedIdentifier> result,
            String raw,
            IdentifierType expectedType
    ) {
        log.info("RP-04 식별자 정규화 시작: expectedType={}", expectedType);
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST
            );
        }

        try {
            var item = IdentifierNormalizer.normalize(raw);
            log.info("RP-04 식별자 정규화 결과: actualType={}", item.type());

            if (item.type() != expectedType) {
                throw new BusinessException(
                        ErrorCode.INVALID_REQUEST
                );
            }

            result.add(item);

        } catch (IllegalArgumentException e) {
            log.warn("RP-04 식별자 정규화 실패: expectedType={}", expectedType, e);
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST
            );
        }
    }

    private void registerEvidenceStorageCleanup(
            String pendingKey,
            String reportKey
    ) {
        if (!TransactionSynchronizationManager
                .isSynchronizationActive()) {
            throw new IllegalStateException(
                    "활성화된 트랜잭션이 없습니다."
            );
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {

                    @Override
                    public void afterCompletion(int status) {

                        try {
                            if (status == STATUS_COMMITTED) {
                                // 신고 저장 성공: 임시 파일 정리
                                evidenceStore.deletePending(pendingKey);

                            } else {
                                // 신고 저장 실패: 최종 복사본 정리
                                evidenceStore.deleteReport(reportKey);
                            }

                        } catch (Exception e) {
                            log.error(
                                    "신고 증빙 Storage 정리 실패. pendingKey={}, reportKey={}, status={}",
                                    pendingKey,
                                    reportKey,
                                    status,
                                    e
                            );
                        }
                    }
                }
        );
    }
}
