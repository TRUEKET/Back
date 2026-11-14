
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
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ReportCreationService {

    private final EntityManager entityManager;
    private final ReportRepository reportRepository;
    private final ReportIdentifierRepository reportIdentifierRepository;
    private final IdentifierRepository identifierRepository;
    private final IdentifierHashService identifierHashService;
    private final IdentifierEncryptionService encryptionService;

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

        // 4. JD 식별자 목록 결합
        List<String> rawIdentifiers = Stream.of(
                        source.identifiers().accounts(),
                        source.identifiers().phones(),
                        source.identifiers().xAccounts()
                )
                .flatMap(List::stream)
                .toList();

        // 5. 정규화 및 중복 제거
        List<IdentifierNormalizer.NormalizedIdentifier> normalized =
                new ArrayList<>();

        Set<String> unique = new HashSet<>();

        for (String raw : rawIdentifiers) {
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

        // 7. JD 근거 말풍선 저장
        ArrayNode evidenceQuotesJson =
                JsonNodeFactory.instance.arrayNode();

        for (var evidence : source.evidence()) {
            evidenceQuotesJson.add(evidence.quote());
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
        if (source == null
                || kind == null
                || source.identifiers() == null
                || source.evidence() == null) {
            throw new BusinessException(
                    ErrorCode.REPORT_NOT_ALLOWED
            );
        }

        // 현재 AnalysisReportSource에는
        // showReportButton, raisedByReports가 포함되어 있지 않음.
        //
        // 따라서 SAFE 단계 신고 금지 및
        // 신고 목록만으로 상승한 의심 제보 금지를
        // 서버에서 검증할 수 없는 상태.
        //
        // JD 반환 계약이 보완되기 전까지
        // 신고 DB 저장을 허용하지 않음.
        throw new BusinessException(
                ErrorCode.ANALYSIS_NOT_AVAILABLE
        );
    }
}
