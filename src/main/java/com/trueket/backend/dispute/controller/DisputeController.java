
package com.trueket.backend.dispute.controller;

import com.trueket.backend.dispute.dto.*;
import com.trueket.backend.dispute.entity.enums.DisputeReason;
import com.trueket.backend.dispute.service.*;
import com.trueket.backend.global.exception.ApiException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(
        name = "RP-08 이의제기",
        description = "이의제기 가능 여부 조회, 정상 거래·환불 이의제기 접수, 신고 유지·삭제 및 재게시 API"
)
@RestController
@RequestMapping("/disputes")
@RequiredArgsConstructor
public class DisputeController {

    private final DisputeAvailabilityService availabilityService;
    private final DisputeCreateService createService;
    private final DisputeDecisionService decisionService;
    private final DisputeRepublishService republishService;

    // =========================================================
    // RP-08-01. 이의제기 가능 여부 조회
    // =========================================================

    @Operation(
            summary = "이의제기 가능 여부 조회",
            description = """
                    계좌번호, 전화번호 또는 SNS 계정으로 이의제기
                    신청 가능 여부와 대상 신고 수를 조회합니다.

                    [반환 상태]
                    - AVAILABLE: 최초 신청 가능
                    - NEW_REPORTS_ONLY: 새로운 신고에 한해 신청 가능
                    - PROCESSING: 이의제기 처리 중
                    - NO_NEW_REPORTS: 새롭게 이의제기할 신고 없음

                    로그인 불필요.

                    조회는 계좌·전화·SNS를 지원하지만,
                    실제 이의제기 접수는 은행 계좌만 지원합니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "이의제기 가능 여부 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(
                                    implementation = DisputeAvailabilityResponse.class
                            ),
                            examples = {
                                    @ExampleObject(
                                            name = "신규 신고 신청 가능",
                                            value = """
                                                    {
                                                      "status": "NEW_REPORTS_ONLY",
                                                      "canApply": true,
                                                      "targetReportCount": 3
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "최초 신청 가능",
                                            value = """
                                                    {
                                                      "status": "AVAILABLE",
                                                      "canApply": true,
                                                      "targetReportCount": 3
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "처리 중",
                                            value = """
                                                    {
                                                      "status": "PROCESSING",
                                                      "canApply": false,
                                                      "targetReportCount": 0
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "신규 신고 없음",
                                            value = """
                                                    {
                                                      "status": "NO_NEW_REPORTS",
                                                      "canApply": false,
                                                      "targetReportCount": 0
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 식별자 입력",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "message": "올바른 식별자를 입력해 주세요."
                                            }
                                            """
                            )
                    )
            )
    })
    @GetMapping("/availability")
    public ResponseEntity<DisputeAvailabilityResponse> checkAvailability(

            @Parameter(
                    description = "조회할 계좌번호, 전화번호 또는 SNS 계정",
                    example = "신한 111-111-1-111111",
                    required = true
            )
            @RequestParam("q") String q
    ) {
        return ResponseEntity.ok(
                availabilityService.check(q)
        );
    }

    // =========================================================
    // RP-08-02. 이의제기 접수
    // =========================================================

    @Operation(
            summary = "이의제기 신청",
            description = """
                    판매자가 정상 거래 또는 환불 완료 증빙을 제출합니다.

                    로그인 불필요.

                    [현재 지원]
                    - 은행 계좌 이의제기만 가능
                    - NORMAL: 표 전달 증빙 + 입금 증빙
                    - REFUND: 환불 이체확인증만 제출

                    [처리 결과]
                    - DELIVERED: 검증 통과, 게시자에게 전달
                    - REJECTED: 증빙 조건 불일치

                    증빙 검증에서 거절된 경우에도
                    HTTP 200과 REJECTED 상태를 반환합니다.
                    """
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            description = "이의제기 신청 정보 및 증빙 이미지",
            content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                    schema = @Schema(
                            implementation = DisputeCreateSwaggerRequest.class
                    )
            )
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "접수 처리 완료 (DELIVERED 또는 REJECTED)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(
                                    implementation = DisputeCreateResponse.class
                            ),
                            examples = {
                                    @ExampleObject(
                                            name = "검증 통과",
                                            value = """
                                                    {
                                                      "disputeId": 19,
                                                      "receiptNo": "TK-2611-000007",
                                                      "status": "DELIVERED",
                                                      "targetReportCount": 3,
                                                      "message": "증빙 검증을 통과하여 이의제기가 전달되었습니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "증빙 검증 거절",
                                            value = """
                                                    {
                                                      "disputeId": 20,
                                                      "receiptNo": "TK-2611-000008",
                                                      "status": "REJECTED",
                                                      "targetReportCount": 0,
                                                      "message": "증빙 확인 결과가 이의제기 조건과 일치하지 않습니다."
                                                    }
                                                    """
                                    )
                            }
                    )
            ),

            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 계좌, 이메일, 필수 증빙 누락 또는 지원하지 않는 사유",
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(
                                            name = "정상 거래 증빙 누락",
                                            value = """
                                        {
                                          "message": "정상 거래는 표 전달 내역과 입금 내역이 모두 필요합니다."
                                        }
                                        """
                                    ),
                                    @ExampleObject(
                                            name = "환불 증빙 누락",
                                            value = """
                                        {
                                          "message": "환불 이체확인증이 필요합니다."
                                        }
                                        """
                                    ),
                                    @ExampleObject(
                                            name = "환불에 불필요한 증빙 첨부",
                                            value = """
                                        {
                                          "message": "환불 이의제기에는 환불 증빙만 첨부해 주세요."
                                        }
                                        """
                                    ),
                                    @ExampleObject(
                                            name = "잘못된 이메일",
                                            value = """
                                        {
                                          "message": "올바른 이메일 주소를 입력해 주세요."
                                        }
                                        """
                                    ),
                                    @ExampleObject(
                                            name = "지원하지 않는 사유",
                                            value = """
                                        {
                                          "message": "현재는 정상 거래와 환불 이의제기만 지원합니다."
                                        }
                                        """
                                    )
                            }
                    )
            ),

            @ApiResponse(
                    responseCode = "409",
                    description = "이미 처리 중인 이의제기 또는 새로운 대상 신고 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(
                                            name = "처리 중",
                                            value = """
                                                    {
                                                      "message": "현재 처리 중인 이의제기가 있습니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "신규 신고 없음",
                                            value = """
                                                    {
                                                      "message": "새롭게 이의제기할 기록이 없습니다."
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "AI 분석 또는 Storage 처리 장애",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "message": "증빙 확인 중 오류가 발생했습니다. 접수번호를 보관하고 다시 신청해 주세요."
                                            }
                                            """
                            )
                    )
            )
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DisputeCreateResponse> create(

            @Parameter(hidden = true)
            @RequestParam("identifier")
            String identifier,

            @Parameter(hidden = true)
            @RequestParam("reason")
            DisputeReason reason,

            @Parameter(hidden = true)
            @RequestParam(
                    value = "description",
                    required = false
            )
            String description,

            @Parameter(hidden = true)
            @RequestParam("sellerEmail")
            String sellerEmail,

            @Parameter(hidden = true)
            @RequestParam(
                    value = "deliveryEvidence",
                    required = false
            )
            MultipartFile deliveryEvidence,

            @Parameter(hidden = true)
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

    // =========================================================
    // RP-08-03. 신고 유지 또는 삭제
    // =========================================================

    @Operation(
            summary = "이의제기 유지·삭제 결정",
            description = """
                    신고 게시자가 전달받은 이의제기에 응답합니다.

                    [결정]
                    - MAINTAINED: 기존 신고 유지
                    - DELETED: 기존 신고 삭제

                    [조건]
                    - 로그인한 신고 작성자만 가능
                    - RESPONDING 상태만 결정 가능
                    - 이의제기 응답 기한은 3일
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "신고 유지 또는 삭제 성공",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(
                                    implementation = DisputeDecisionResponse.class
                            ),
                            examples = {
                                    @ExampleObject(
                                            name = "신고 유지",
                                            value = """
                                                    {
                                                      "disputeId": 18,
                                                      "reportId": 359,
                                                      "decision": "MAINTAINED",
                                                      "message": "신고 유지가 완료되었습니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "신고 삭제",
                                            value = """
                                                    {
                                                      "disputeId": 18,
                                                      "reportId": 360,
                                                      "decision": "DELETED",
                                                      "message": "신고 삭제가 완료되었습니다."
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "유지 또는 삭제 외의 결정 요청",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "message": "유지 또는 삭제만 선택할 수 있습니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "로그인 필요",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "message": "로그인이 필요합니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "대상 신고가 없거나 본인 신고가 아님",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "message": "이의제기 대상 신고를 찾을 수 없습니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "이미 처리된 이의제기 또는 응답 기한 초과",
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(
                                            name = "이미 처리됨",
                                            value = """
                                                    {
                                                      "message": "이미 처리된 이의제기입니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "기한 초과",
                                            value = """
                                                    {
                                                      "message": "이의제기 응답 기한이 지났습니다."
                                                    }
                                                    """
                                    )
                            }
                    )
            )
    })
    @PostMapping("/{disputeId}/decision")
    public ResponseEntity<DisputeDecisionResponse> decide(

            @Parameter(
                    description = "이의제기 ID",
                    example = "18",
                    required = true
            )
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

    // =========================================================
    // RP-08-04. 비공개 신고 재게시
    // =========================================================

    @Operation(
            summary = "비공개 신고 재게시",
            description = """
                    이의제기 응답 기한이 지나 자동 비공개된 신고를
                    원래 작성자가 다시 게시하는 API입니다.

                    [재게시 조건]
                    - 로그인한 신고 작성자
                    - 현재 PRIVATE 상태
                    - 이전에 재게시한 이력이 없어야 함

                    재게시 기회는 1회입니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "재게시 성공",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(
                                    implementation = DisputeDecisionResponse.class
                            ),
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "disputeId": 18,
                                              "reportId": 361,
                                              "decision": "MAINTAINED",
                                              "message": "신고가 다시 공개되었습니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "message": "잘못된 요청입니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "로그인 필요",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "message": "로그인이 필요합니다."
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "이의제기 대상 신고를 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "message": "이의제기 대상 신고를 찾을 수 없습니다."
                                            }
                                            """
                            )
                    )
            ),

            @ApiResponse(
                    responseCode = "409",
                    description = "비공개 상태가 아니거나 이미 재게시한 신고",
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(
                                            name = "비공개 상태 아님",
                                            value = """
                                        {
                                          "message": "비공개 상태의 신고만 재게시할 수 있습니다."
                                        }
                                        """
                                    ),
                                    @ExampleObject(
                                            name = "이미 재게시함",
                                            value = """
                                        {
                                          "message": "이미 재게시한 신고입니다."
                                        }
                                        """
                                    ),
                                    @ExampleObject(
                                            name = "신고 원본 상태 불일치",
                                            value = """
                                        {
                                          "message": "현재 신고 상태에서는 재게시할 수 없습니다."
                                        }
                                        """
                                    )
                            }
                    )
            )

    })
    @PostMapping("/{disputeId}/republish")
    public ResponseEntity<DisputeDecisionResponse> republish(

            @Parameter(
                    description = "이의제기 ID",
                    example = "18",
                    required = true
            )
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

    // =========================================================
    // 공통 인증 사용자 ID 조회
    // =========================================================

    private Long authenticatedUserId(
            Authentication authentication
    ) {

        if (authentication == null
                || !authentication.isAuthenticated()) {

            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "로그인이 필요합니다."
            );
        }

        try {

            return Long.valueOf(
                    authentication.getName()
            );

        } catch (NumberFormatException e) {

            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "로그인이 필요합니다."
            );
        }
    }
}
