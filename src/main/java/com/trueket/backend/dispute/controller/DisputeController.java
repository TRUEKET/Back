
package com.trueket.backend.dispute.controller;

import com.trueket.backend.dispute.dto.*;
import com.trueket.backend.dispute.entity.enums.DisputeReason;
import com.trueket.backend.dispute.service.*;
import com.trueket.backend.global.exception.ApiException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/disputes")
@RequiredArgsConstructor
public class DisputeController {

    private final DisputeAvailabilityService availabilityService;
    private final DisputeCreateService createService;
    private final DisputeDecisionService decisionService;
    private final DisputeRepublishService republishService;

    @GetMapping("/availability")
    public ResponseEntity<DisputeAvailabilityResponse> checkAvailability(
            @RequestParam("q") String q
    ) {
        return ResponseEntity.ok(
                availabilityService.check(q)
        );
    }

    // RP-08 이의제기 접수
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DisputeCreateResponse> create(
            @RequestParam("identifier") String identifier,
            @RequestParam("reason") DisputeReason reason,

            @RequestParam(
                    value = "description",
                    required = false
            )
            String description,

            @RequestParam("sellerEmail") String sellerEmail,

            @RequestParam(
                    value = "deliveryEvidence",
                    required = false
            )
            MultipartFile deliveryEvidence,

            @RequestParam(
                    value = "transferEvidence",
                    required = false
            )
            MultipartFile transferEvidence
    ) {

        if (sellerEmail == null
                || !sellerEmail.matches(
                "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"
        )
                || sellerEmail.length() > 254) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "올바른 이메일 주소를 입력해 주세요."
            );
        }

        if (reason == DisputeReason.NORMAL) {

            if (deliveryEvidence == null
                    || deliveryEvidence.isEmpty()
                    || transferEvidence == null
                    || transferEvidence.isEmpty()) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "정상 거래는 표 전달 내역과 입금 내역이 모두 필요합니다."
                );
            }

        } else if (reason == DisputeReason.REFUND) {

            if (transferEvidence == null
                    || transferEvidence.isEmpty()) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "환불 이체확인증이 필요합니다."
                );
            }

            if (deliveryEvidence != null
                    && !deliveryEvidence.isEmpty()) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "환불 이의제기에는 환불 증빙만 첨부해 주세요."
                );
            }

        } else {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "현재는 정상 거래와 환불 이의제기만 지원합니다."
            );
        }

        return ResponseEntity.ok(
                createService.create(
                        identifier,
                        reason,
                        description,
                        sellerEmail,
                        deliveryEvidence,
                        transferEvidence
                )
        );
    }

    // 신고 게시자: 유지 또는 삭제
    @PostMapping("/{disputeId}/decision")
    public ResponseEntity<DisputeDecisionResponse> decide(
            @PathVariable Long disputeId,
            @Valid @RequestBody DisputeDecisionRequest request,
            Authentication authentication
    ) {
        Long userId = authenticatedUserId(authentication);

        return ResponseEntity.ok(
                decisionService.decide(
                        userId,
                        disputeId,
                        request.reportId(),
                        request.decision()
                )
        );
    }

    // 신고 게시자: 비공개 후 1회 재게시
    @PostMapping("/{disputeId}/republish")
    public ResponseEntity<DisputeDecisionResponse> republish(
            @PathVariable Long disputeId,
            @Valid @RequestBody DisputeRepublishRequest request,
            Authentication authentication
    ) {
        Long userId = authenticatedUserId(authentication);

        return ResponseEntity.ok(
                republishService.republish(
                        userId,
                        disputeId,
                        request.reportId()
                )
        );
    }

    private Long authenticatedUserId(Authentication authentication) {

        if (authentication == null
                || !authentication.isAuthenticated()) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "로그인이 필요합니다."
            );
        }

        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException e) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "로그인이 필요합니다."
            );
        }
    }
}
