package com.trueket.backend.report.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.trueket.backend.global.exception.BusinessException;
import com.trueket.backend.global.exception.ErrorCode;
import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.identifier.repository.IdentifierRepository;
import com.trueket.backend.identifier.service.IdentifierEncryptionService;
import com.trueket.backend.identifier.util.IdentifierMasker;
import com.trueket.backend.lookup.util.IdentifierHashService;
import com.trueket.backend.lookup.util.IdentifierNormalizer;
import com.trueket.backend.report.dto.ReportCreateRequest;
import com.trueket.backend.report.dto.ReportCreateResponse;
import com.trueket.backend.report.entity.Report;
import com.trueket.backend.report.entity.ReportIdentifier;
import com.trueket.backend.report.entity.enums.ReportKind;
import com.trueket.backend.report.repository.ReportIdentifierRepository;
import com.trueket.backend.report.repository.ReportRepository;
import com.trueket.backend.user.entity.User;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReportCreationService {

    private final EntityManager entityManager;
    private final ReportRepository reportRepository;
    private final ReportIdentifierRepository reportIdentifierRepository;
    private final IdentifierRepository identifierRepository;
    private final IdentifierHashService identifierHashService;
    private final IdentifierEncryptionService encryptionService;
    private final AnalysisVerificationGateway analysisGateway;

    @Transactional
    public ReportCreateResponse create(
            Long authenticatedUserId,
            ReportCreateRequest request
    ) {
        // 1. 서버에서 판독 결과 검증
        AnalysisVerificationGateway.VerifiedAnalysis verified =
                analysisGateway.verify(
                        authenticatedUserId,
                        request.analysisProof()
                );

        validateAnalysis(verified);

        // 2. 사용자 조회 및 중복 생성 방지를 위한 잠금
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

        // 3. 검증된 식별자 정규화 및 중복 제거
        List<IdentifierNormalizer.NormalizedIdentifier> normalized =
                new ArrayList<>();

        Set<String> unique = new HashSet<>();

        for (String raw : verified.identifiers()) {
            IdentifierNormalizer.NormalizedIdentifier item =
                    IdentifierNormalizer.normalize(raw);

            String hash = identifierHashService.hash(
                    item.type(),
                    item.value()
            );

            if (unique.add(item.type().name() + ":" + hash)) {
                normalized.add(item);
            }
        }

        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        // 4. 동일 신고자의 동일 계좌 피해 신고 중복 확인
        if (verified.kind() == ReportKind.FRAUD) {
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

        // 5. 신고 생성
        List<String> links = request.referenceLinks() == null
                ? List.of()
                : request.referenceLinks();

        ArrayNode evidenceQuotesJson = JsonNodeFactory.instance.arrayNode();

        for (String quote : verified.evidenceQuotes()) {
            evidenceQuotesJson.add(quote);
        }

        ArrayNode referenceLinksJson = JsonNodeFactory.instance.arrayNode();

        for (String link : links) {
            referenceLinksJson.add(link);
        }

        Report report = Report.create(
                user,
                user.getPhoneHash(),
                verified.kind(),
                request.tradeType(),
                evidenceQuotesJson,
                referenceLinksJson,
                request.sentDate()
        );

        reportRepository.saveAndFlush(report);

        // 6. 식별자 조회 또는 생성
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

            // 7. 신고와 식별자 연결
            reportIdentifierRepository.save(
                    ReportIdentifier.create(
                            report,
                            identifier
                    )
            );

            identifierIds.add(identifier.getId());
        }

        reportIdentifierRepository.flush();

        // 8. 응답
        return new ReportCreateResponse(
                report.getId(),
                report.getKind(),
                report.getStatus(),
                List.copyOf(identifierIds)
        );
    }

    private void validateAnalysis(
            AnalysisVerificationGateway.VerifiedAnalysis verified
    ) {
        if (verified == null
                || verified.kind() == null
                || !verified.reportable()
                || verified.identifiers() == null
                || verified.identifiers().isEmpty()
                || verified.evidenceQuotes() == null) {
            throw new BusinessException(
                    ErrorCode.REPORT_NOT_ALLOWED
            );
        }

        if (verified.kind() == ReportKind.SUSPICION
                && verified.suspicionFromListOnly()) {
            throw new BusinessException(
                    ErrorCode.REPORT_NOT_ALLOWED
            );
        }
    }
}
