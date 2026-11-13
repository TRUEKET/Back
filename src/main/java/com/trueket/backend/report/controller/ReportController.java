
package com.trueket.backend.report.controller;

import com.trueket.backend.global.response.ApiResponse;
import com.trueket.backend.report.dto.ReportDetailResponse;
import com.trueket.backend.report.dto.ReportListResponse;
import com.trueket.backend.report.service.ReportService;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/me/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    // RP-06: 내 신고 목록 조회
    @GetMapping
    public ResponseEntity<ApiResponse<Page<ReportListResponse>>> getMyReports(
            Authentication authentication,
            Pageable pageable
    ) {
        Long userId = Long.valueOf(authentication.getName());

        Page<ReportListResponse> reports =
                reportService.getMyReports(userId, pageable);

        return ResponseEntity.ok(
                ApiResponse.success(reports)
        );
    }

    // RP-06: 내 신고 상세 조회
    @GetMapping("/{reportId}")
    public ResponseEntity<ApiResponse<ReportDetailResponse>> getMyReportDetail(
            Authentication authentication,
            @PathVariable Long reportId
    ) {
        Long userId = Long.valueOf(authentication.getName());

        ReportDetailResponse report =
                reportService.getMyReportDetail(userId, reportId);

        return ResponseEntity.ok(
                ApiResponse.success(report)
        );
    }

    // RP-07: 내 신고 삭제
    @DeleteMapping("/{reportId}")
    public ResponseEntity<ApiResponse<Void>> deleteMyReport(
            Authentication authentication,
            @PathVariable Long reportId
    ) {
        Long userId = Long.valueOf(authentication.getName());

        reportService.deleteMyReport(userId, reportId);

        return ResponseEntity.ok(
                ApiResponse.success(null)
        );
    }
}
