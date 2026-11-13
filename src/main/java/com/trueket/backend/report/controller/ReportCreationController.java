
package com.trueket.backend.report.controller;

import com.trueket.backend.global.response.ApiResponse;
import com.trueket.backend.report.dto.ReportCreateRequest;
import com.trueket.backend.report.dto.ReportCreateResponse;
import com.trueket.backend.report.service.ReportCreationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportCreationController {

    private final ReportCreationService reportCreationService;

    // RP-04: 판독 결과 신고 기록
    @PostMapping
    public ResponseEntity<ApiResponse<ReportCreateResponse>> createReport(
            Authentication authentication,
            @Valid @RequestBody ReportCreateRequest request
    ) {
        Long userId = Long.valueOf(authentication.getName());

        ReportCreateResponse result =
                reportCreationService.create(userId, request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(result));
    }
}
