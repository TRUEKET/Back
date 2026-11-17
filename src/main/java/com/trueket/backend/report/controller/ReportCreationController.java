
package com.trueket.backend.report.controller;

import com.trueket.backend.global.response.ApiResponse;
import com.trueket.backend.report.dto.ReportCreateRequest;
import com.trueket.backend.report.dto.ReportCreateResponse;
import com.trueket.backend.report.service.ReportCreationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(
        name = "RP-04 신고 등록",
        description = "JD 판독 결과를 기반으로 피해 신고 및 의심 제보를 등록하는 API"
)
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportCreationController {

    private final ReportCreationService reportCreationService;

    @Operation(
            summary = "신고·제보 등록",
            description = """
                    JD 판독 결과를 기반으로 신고 또는 제보를 등록합니다.

                    [신고 종류]
                    - FRAUD: 피해 신고
                    - SUSPICION: 의심 제보

                    [신고 조건]
                    - 로그인 및 휴대폰 인증 완료
                    - JD 판독 결과와 동일한 로그인 세션 필요
                    - JD 결과에서 신고 등록이 허용되어야 함
                    - SUSPICION은 별도의 의심 제보 허용 조건 필요
                    - FRAUD는 동일 휴대폰·동일 계좌 중복 신고 불가

                    [증빙]
                    - evidenceToken은 선택값
                    - RP-05에서 발급한 토큰 사용
                    - 토큰 사용 시 사용자·판독 ID·계좌 일치 여부 검증
                    - 토큰은 일회용
                    """
    )

    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "신고·제보 등록 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(
                                            name = "피해 신고 등록 성공",
                                            value = """
                                        {
                                          "code": "SUCCESS",
                                          "message": "요청이 성공했습니다.",
                                          "data": {
                                            "reportId": 365,
                                            "kind": "FRAUD",
                                            "status": "PUBLISHED",
                                            "identifierIds": [
                                              308,
                                              309
                                            ]
                                          }
                                        }
                                        """
                                    ),
                                    @ExampleObject(
                                            name = "의심 제보 등록 성공",
                                            value = """
                                        {
                                          "code": "SUCCESS",
                                          "message": "요청이 성공했습니다.",
                                          "data": {
                                            "reportId": 366,
                                            "kind": "SUSPICION",
                                            "status": "PUBLISHED",
                                            "identifierIds": [
                                              310
                                            ]
                                          }
                                        }
                                        """
                                    )
                            }
                    )
            ),


            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 또는 식별자 정규화 실패",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                        {
                                          "code": "INVALID_REQUEST",
                                          "message": "잘못된 요청입니다.",
                                          "data": null
                                        }
                                        """
                            )
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "로그인 필요 또는 세션 사용자 불일치",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                        {
                                          "code": "UNAUTHORIZED",
                                          "message": "인증이 필요합니다.",
                                          "data": null
                                        }
                                        """
                            )
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "계정 상태 또는 휴대폰 인증 조건 미충족",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                        {
                                          "code": "FORBIDDEN",
                                          "message": "접근 권한이 없습니다.",
                                          "data": null
                                        }
                                        """
                            )
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "JD 판독 세션 만료 또는 조회 불가",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                        {
                                          "message": "판독 세션이 끝났어요. 다시 판독해 주세요"
                                        }
                                        """
                            )
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "동일 계좌 피해 신고 중복",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                        {
                                          "code": "DUPLICATE_REPORT",
                                          "message": "이미 동일한 계좌에 피해 신고를 등록했습니다.",
                                          "data": null
                                        }
                                        """
                            )
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "현재 JD 판독 결과로 신고 등록 불가",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                        {
                                          "code": "REPORT_NOT_ALLOWED",
                                          "message": "현재 판독 결과로는 신고를 기록할 수 없습니다.",
                                          "data": null
                                        }
                                        """
                            )
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "판독 검증 또는 증빙 Storage 처리 불가",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                        {
                                          "message": "신고 증빙 사진을 저장하지 못했어요. 잠시 후 다시 시도해 주세요."
                                        }
                                        """
                            )
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "서버 내부 오류",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                        {
                                          "code": "INTERNAL_SERVER_ERROR",
                                          "message": "서버 내부 오류가 발생했습니다.",
                                          "data": null
                                        }
                                        """
                            )
                    )
            )
    })

    @PostMapping
    public ResponseEntity<ApiResponse<ReportCreateResponse>> createReport(
            Authentication authentication,
            HttpServletRequest httpRequest,
            @Valid @RequestBody ReportCreateRequest request
    ) {
        Long userId = Long.valueOf(authentication.getName());

        ReportCreateResponse result =
                reportCreationService.create(
                        userId,
                        request,
                        httpRequest.getSession(false)
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(result));
    }
}
