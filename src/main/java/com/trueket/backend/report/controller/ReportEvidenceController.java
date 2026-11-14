
package com.trueket.backend.report.controller;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.global.response.ApiResponse;
import com.trueket.backend.report.dto.ReportEvidenceResponse;
import com.trueket.backend.report.service.ReportEvidenceService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportEvidenceController {

    private final ReportEvidenceService reportEvidenceService;

    @PostMapping(
            value = "/evidence",
            consumes = "multipart/form-data"
    )
    public ResponseEntity<ApiResponse<ReportEvidenceResponse>> verifyEvidence(
            Authentication authentication,
            HttpServletRequest httpRequest,
            @RequestParam("analysisId") UUID analysisId,
            @RequestParam("account") String account,
            @RequestPart("file") MultipartFile file
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "로그인이 필요합니다."
            );
        }

        Long userId;

        try {
            userId = Long.valueOf(authentication.getName());
        } catch (NumberFormatException e) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "로그인이 필요합니다."
            );
        }

        ReportEvidenceResponse result =
                reportEvidenceService.verify(
                        userId,
                        analysisId,
                        account,
                        file,
                        httpRequest.getSession(false)
                );

        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
