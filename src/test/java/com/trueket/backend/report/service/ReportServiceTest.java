package com.trueket.backend.report.service;

import com.trueket.backend.global.exception.BusinessException;
import com.trueket.backend.report.entity.Report;
import com.trueket.backend.report.repository.ReportRepository;
import com.trueket.backend.report.entity.ReportIdentifier;
import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.report.repository.ReportIdentifierRepository;
import com.trueket.backend.report.dto.ReportDetailResponse;
import com.trueket.backend.report.repository.EvidenceRepository;
import com.trueket.backend.dispute.repository.DisputeItemRepository;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private ReportRepository reportRepository;
    @Mock
    private ReportIdentifierRepository reportIdentifierRepository;
    @Mock
    private EvidenceRepository evidenceRepository;
    @Mock
    private DisputeItemRepository disputeItemRepository;


    @InjectMocks
    private ReportService reportService;

    private Long userId;
    private PageRequest pageable;

    @BeforeEach
    void setUp() {
        userId = 1L;
        pageable = PageRequest.of(0, 10);
    }

    @Test
    void 내_신고_목록이_없으면_빈_페이지를_반환한다() {

        // given
        when(reportRepository
                .findAllByUser_IdOrderByCreatedAtDesc(userId, pageable))
                .thenReturn(Page.empty(pageable));

        // when
        var result = reportService.getMyReports(userId, pageable);

        // then
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();

        verify(reportRepository)
                .findAllByUser_IdOrderByCreatedAtDesc(userId, pageable);
    }

    @Test
    void 내_신고_목록을_조회하면_DTO로_변환한다() {

        // given
        Report report = mock(Report.class);

        when(report.getId()).thenReturn(10L);

        Page<Report> reports =
                new PageImpl<>(List.of(report), pageable, 1);

        when(reportRepository
                .findAllByUser_IdOrderByCreatedAtDesc(userId, pageable))
                .thenReturn(reports);

        // when
        var result = reportService.getMyReports(userId, pageable);

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).reportId())
                .isEqualTo(10L);
    }

    @Test
    void 내_신고_상세_조회에_성공한다() {

        // given
        Long userId = 1L;
        Long reportId = 10L;

        Report report = mock(Report.class);

        when(report.getId()).thenReturn(reportId);

        when(reportRepository.findByIdAndUser_Id(reportId, userId))
                .thenReturn(Optional.of(report));

        when(reportIdentifierRepository.findAllByReport_Id(reportId))
                .thenReturn(List.of());

        // when
        ReportDetailResponse result =
                reportService.getMyReportDetail(userId, reportId);

        // then
        assertThat(result.reportId()).isEqualTo(reportId);
        assertThat(result.identifiers()).isEmpty();

        verify(reportRepository)
                .findByIdAndUser_Id(reportId, userId);
    }

    @Test
    void 본인_신고가_아니면_상세_조회에_실패한다() {

        // given
        Long userId = 1L;
        Long reportId = 99L;

        when(reportRepository.findByIdAndUser_Id(reportId, userId))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                reportService.getMyReportDetail(userId, reportId)
        )
                .isInstanceOf(BusinessException.class)
                .hasMessage("신고 기록을 찾을 수 없습니다.");

        verifyNoInteractions(reportIdentifierRepository);
    }

    @Test
    void 신고_상세_조회시_마스킹된_식별자를_반환한다() {

        // given
        Long userId = 1L;
        Long reportId = 10L;

        Report report = mock(Report.class);
        Identifier identifier = mock(Identifier.class);
        ReportIdentifier reportIdentifier =
                mock(ReportIdentifier.class);

        when(report.getId()).thenReturn(reportId);

        when(reportRepository.findByIdAndUser_Id(reportId, userId))
                .thenReturn(Optional.of(report));

        when(reportIdentifierRepository.findAllByReport_Id(reportId))
                .thenReturn(List.of(reportIdentifier));

        when(reportIdentifier.isHidden()).thenReturn(false);
        when(reportIdentifier.getIdentifier()).thenReturn(identifier);

        when(identifier.getId()).thenReturn(5L);
        when(identifier.getType()).thenReturn(
                com.trueket.backend.identifier.entity.enums.IdentifierType.ACCOUNT
        );
        when(identifier.getValueMasked()).thenReturn("123-***-456");

        // when
        ReportDetailResponse result =
                reportService.getMyReportDetail(userId, reportId);

        // then
        assertThat(result.identifiers()).hasSize(1);
        assertThat(result.identifiers().get(0).identifierId())
                .isEqualTo(5L);
        assertThat(result.identifiers().get(0).maskedValue())
                .isEqualTo("123-***-456");
    }

    @Test
    void 내_신고를_정상적으로_삭제한다() {

        // given
        Long userId = 1L;
        Long reportId = 10L;

        Report report = mock(Report.class);

        when(reportRepository.findByIdAndUser_Id(reportId, userId))
                .thenReturn(Optional.of(report));

        // when
        reportService.deleteMyReport(userId, reportId);

        // then
        InOrder inOrder = inOrder(
                disputeItemRepository,
                evidenceRepository,
                reportIdentifierRepository,
                reportRepository
        );

        inOrder.verify(disputeItemRepository)
                .deleteAllByReport_Id(reportId);

        inOrder.verify(evidenceRepository)
                .deleteAllByReport_Id(reportId);

        inOrder.verify(reportIdentifierRepository)
                .deleteAllByReport_Id(reportId);

        inOrder.verify(reportRepository)
                .delete(report);
    }

    @Test
    void 다른_사람의_신고는_삭제할_수_없다() {

        // given
        Long userId = 1L;
        Long reportId = 99L;

        when(reportRepository.findByIdAndUser_Id(reportId, userId))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                reportService.deleteMyReport(userId, reportId)
        )
                .isInstanceOf(BusinessException.class)
                .hasMessage("신고 기록을 찾을 수 없습니다.");

        verifyNoInteractions(
                disputeItemRepository,
                evidenceRepository,
                reportIdentifierRepository
        );

        verify(reportRepository, never())
                .delete(any(Report.class));
    }

    @Test
    void 신고_삭제_중_오류가_발생하면_이후_삭제를_진행하지_않는다() {

        // given
        Long userId = 1L;
        Long reportId = 10L;

        Report report = mock(Report.class);

        when(reportRepository.findByIdAndUser_Id(reportId, userId))
                .thenReturn(Optional.of(report));

        doThrow(new RuntimeException("DB 삭제 오류"))
                .when(evidenceRepository)
                .deleteAllByReport_Id(reportId);

        // when & then
        assertThatThrownBy(() ->
                reportService.deleteMyReport(userId, reportId)
        )
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB 삭제 오류");

        verify(disputeItemRepository)
                .deleteAllByReport_Id(reportId);

        verify(reportIdentifierRepository, never())
                .deleteAllByReport_Id(anyLong());

        verify(reportRepository, never())
                .delete(any(Report.class));
    }
}