
package com.trueket.backend.report.service;

import com.trueket.backend.dispute.dto.DisputeNoticeResponse;
import com.trueket.backend.dispute.repository.DisputeItemRepository;
import com.trueket.backend.dispute.service.DisputeEvidenceStorageService;
import com.trueket.backend.global.exception.BusinessException;
import com.trueket.backend.global.exception.ErrorCode;
import com.trueket.backend.report.dto.ReportDetailResponse;
import com.trueket.backend.report.dto.ReportIdentifierResponse;
import com.trueket.backend.report.dto.ReportListResponse;
import com.trueket.backend.report.entity.Evidence;
import com.trueket.backend.report.entity.Report;
import com.trueket.backend.report.entity.ReportIdentifier;
import com.trueket.backend.report.repository.EvidenceRepository;
import com.trueket.backend.report.repository.ReportIdentifierRepository;
import com.trueket.backend.report.repository.ReportRepository;
import com.trueket.backend.dispute.dto.DisputeDetailResponse;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private final ReportRepository reportRepository;
    private final ReportIdentifierRepository reportIdentifierRepository;
    private final EvidenceRepository evidenceRepository;
    private final DisputeItemRepository disputeItemRepository;
    private final EntityManager entityManager;
    private final DisputeEvidenceStorageService disputeEvidenceStorageService;

    // Supabase Storage 증빙 파일 관리
    private final SupabaseReportEvidenceStore supabaseReportEvidenceStore;

    // =====================================================
    // RP-06. 내 신고 목록 조회
    // =====================================================

    public Page<ReportListResponse> getMyReports(
            Long userId,
            Pageable pageable
    ) {
        Page<Report> reports = reportRepository
                .findAllByUser_IdOrderByCreatedAtDesc(
                        userId,
                        pageable
                );

        List<Long> reportIds = reports.getContent()
                .stream()
                .map(Report::getId)
                .toList();

        if (reportIds.isEmpty()) {
            return reports.map(ReportListResponse::from);
        }

        var disputesByReport = disputeItemRepository
                .findAllForReportIds(reportIds)
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        item -> item.getReport().getId()
                ));

        return reports.map(report -> {
            var items = disputesByReport.getOrDefault(
                    report.getId(),
                    List.of()
            );

            var notices = items.stream()
                    .map(DisputeNoticeResponse::from)
                    .toList();

            return ReportListResponse.from(report, notices);
        });
    }

// =====================================================
// RP-06. 내 신고 상세 조회
// =====================================================

    public ReportDetailResponse getMyReportDetail(
            Long userId,
            Long reportId
    ) {
        Report report = reportRepository
                .findByIdAndUser_Id(reportId, userId)
                .orElseThrow(() ->
                        new BusinessException(
                                ErrorCode.REPORT_NOT_FOUND
                        )
                );

        List<ReportIdentifier> reportIdentifiers =
                reportIdentifierRepository
                        .findAllByReport_Id(reportId);

        List<ReportIdentifierResponse> identifiers =
                reportIdentifiers.stream()
                        .filter(ri -> !ri.isHidden())
                        .map(ri ->
                                ReportIdentifierResponse.from(
                                        ri.getIdentifier()
                                )
                        )
                        .toList();

        // 이의제기 상세 정보 조회
        List<DisputeDetailResponse> disputes =
                disputeItemRepository
                        .findAllByReport_Id(reportId)
                        .stream()
                        .map(item -> DisputeDetailResponse.from(
                                item,
                                disputeEvidenceStorageService
                        ))
                        .toList();

        return ReportDetailResponse.from(
                report,
                identifiers,
                disputes
        );
    }

    // =====================================================
    // RP-07. 내 신고 삭제 + Supabase Storage 증빙 삭제
    // =====================================================

    @Transactional
    public void deleteMyReport(
            Long userId,
            Long reportId
    ) {

        // 1. 본인이 작성한 신고인지 확인
        Report report = reportRepository
                .findByIdAndUser_Id(reportId, userId)
                .orElseThrow(() ->
                        new BusinessException(
                                ErrorCode.REPORT_NOT_FOUND
                        )
                );

        // 2. 해당 신고에 연결된 증빙 이미지 목록 조회
        List<Evidence> evidences = evidenceRepository
                .findAllByReport_IdOrderByCreatedAtAsc(reportId);

        // 3. 이의제기 삭제 이력 보존
        var disputeItems =
                disputeItemRepository.findAllByReport_Id(reportId);

        for (var item : disputeItems) {
            entityManager.createNativeQuery("""
                    INSERT INTO public.dispute_deleted_records
                        (dispute_id, report_id, decided_at)
                    VALUES (?1, ?2, NOW())
                    ON CONFLICT (dispute_id, report_id) DO NOTHING
                    """)
                    .setParameter(1, item.getDispute().getId())
                    .setParameter(2, reportId)
                    .executeUpdate();
        }

        // 4. Supabase Storage에 저장된 실제 증빙 파일 삭제
        for (Evidence evidence : evidences) {
            supabaseReportEvidenceStore.deleteReport(
                    evidence.getStorageKey()
            );
        }

        // 5. 이의제기 연결 데이터 삭제
        disputeItemRepository.deleteAllByReport_Id(reportId);

        // 6. 증빙 DB 데이터 삭제
        evidenceRepository.deleteAllByReport_Id(reportId);

        // 7. 신고-식별자 연결 데이터 삭제
        reportIdentifierRepository.deleteAllByReport_Id(reportId);

        // 8. 신고 원본 삭제
        reportRepository.delete(report);
    }
}
