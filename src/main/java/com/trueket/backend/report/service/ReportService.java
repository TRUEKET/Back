
package com.trueket.backend.report.service;

import com.trueket.backend.dispute.repository.DisputeItemRepository;
import com.trueket.backend.global.exception.BusinessException;
import com.trueket.backend.global.exception.ErrorCode;
import com.trueket.backend.report.dto.ReportDetailResponse;
import com.trueket.backend.report.dto.ReportIdentifierResponse;
import com.trueket.backend.report.dto.ReportListResponse;
import com.trueket.backend.report.entity.Report;
import com.trueket.backend.report.entity.ReportIdentifier;
import com.trueket.backend.report.repository.EvidenceRepository;
import com.trueket.backend.report.repository.ReportIdentifierRepository;
import com.trueket.backend.report.repository.ReportRepository;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private final ReportRepository reportRepository;
    private final ReportIdentifierRepository reportIdentifierRepository;
    private final EvidenceRepository evidenceRepository;
    private final DisputeItemRepository disputeItemRepository;

    // RP-06: 내 신고 목록 조회
    public Page<ReportListResponse> getMyReports(
            Long userId,
            Pageable pageable
    ) {
        return reportRepository
                .findAllByUser_IdOrderByCreatedAtDesc(userId, pageable)
                .map(ReportListResponse::from);
    }

    // RP-06: 내 신고 상세 조회
    public ReportDetailResponse getMyReportDetail(
            Long userId,
            Long reportId
    ) {
        // 본인이 작성한 신고인지 확인
        Report report = reportRepository
                .findByIdAndUser_Id(reportId, userId)
                .orElseThrow(() ->
                        new BusinessException(
                                ErrorCode.REPORT_NOT_FOUND
                        )
                );

        // 신고에 연결된 식별자 조회
        List<ReportIdentifier> reportIdentifiers =
                reportIdentifierRepository
                        .findAllByReport_Id(reportId);

        // 숨김 처리되지 않은 식별자만 반환
        List<ReportIdentifierResponse> identifiers =
                reportIdentifiers.stream()
                        .filter(ri -> !ri.isHidden())
                        .map(ri ->
                                ReportIdentifierResponse.from(
                                        ri.getIdentifier()
                                )
                        )
                        .toList();

        return ReportDetailResponse.from(
                report,
                identifiers
        );
    }

    // RP-07: 내 신고 삭제
    @Transactional
    public void deleteMyReport(
            Long userId,
            Long reportId
    ) {
        // 본인이 작성한 신고인지 확인
        Report report = reportRepository
                .findByIdAndUser_Id(reportId, userId)
                .orElseThrow(() ->
                        new BusinessException(
                                ErrorCode.REPORT_NOT_FOUND
                        )
                );

        // 이의제기 연결 데이터 삭제
        disputeItemRepository.deleteAllByReport_Id(reportId);

        // 신고 증빙 데이터 삭제
        evidenceRepository.deleteAllByReport_Id(reportId);

        // 신고-식별자 연결 데이터 삭제
        reportIdentifierRepository.deleteAllByReport_Id(reportId);

        // 신고 원본 삭제
        reportRepository.delete(report);
    }
}
