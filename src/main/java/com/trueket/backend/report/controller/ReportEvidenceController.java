
package com.trueket.backend.report.controller;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.global.response.ApiResponse;
import com.trueket.backend.report.dto.ReportEvidenceResponse;
import com.trueket.backend.report.service.ReportEvidenceService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Tag(
        name = "RP-05 송금 증빙 검증",
        description = """
                송금 완료 사진을 AI로 검증하고,
                신고 대상 계좌와 비교하여 증빙 토큰을 발급하는 API
                """
)
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportEvidenceController {

    private final ReportEvidenceService reportEvidenceService;

    @Operation(
            summary = "송금 증빙 사진 검증",
            description = """
                    JD 판독 결과에 포함된 신고 대상 계좌와
                    사용자가 업로드한 송금 완료 사진을 비교합니다.

                    [인증]
                    - 로그인 필요
                    - JD 판독 세션 필요
                    - 판독 세션 사용자와 로그인 사용자 일치 필요

                    [요청 방식]
                    - Content-Type: multipart/form-data
                    - analysisId: JD 판독 결과 ID (필수)
                    - accountIndex: JD 계좌 목록의 선택 인덱스 (선택)
                    - account: 은행명과 전체 계좌번호 (선택)
                    - file: 송금 완료 사진 (필수)

                    
                    [계좌 선택]
                    - accountIndex는 JD 계좌 목록의 인덱스이며 0부터 시작
                    - accountIndex를 전달하면 해당 위치의 계좌 선택
                    - account를 전달하면 전체 은행명과 계좌번호로 검증
                    - accountIndex와 account는 동시에 전달할 수 없음
                    - 두 값 모두 생략하면 JD 계좌가 정확히 1개일 때만 자동 선택
                    - JD 계좌가 0개 또는 2개 이상이면 계좌 선택 필수
                    - account에 마스킹 문자(*)가 포함되면 HTTP 400
                    - 잘못된 인덱스나 두 값 동시 전달 시 HTTP 400
                    

                    [이미지]
                    - JPEG 또는 PNG
                    - 최대 5MiB
                    - 최대 40,000,000픽셀

                    [검증]
                    - JD 판독 결과 및 계좌 정보 검증
                    - 송금 완료 화면 확인
                    - 수취 은행명 및 계좌번호 추출
                    - 신고 대상 계좌와 비교
                    - 계좌번호 영역 추출
                    - 개인정보 노출 여부 검증

                    [성공]
                    - HTTP 200
                    - verified=true
                    - evidenceToken 발급

                    [증빙]
                    - 검증을 통과한 계좌번호 영역을 임시 저장
                    - 발급된 evidenceToken은 RP-04 신고 등록 시 사용
                    - 토큰은 만료되며 일회용입니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "송금 증빙 검증 성공 및 evidenceToken 발급",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "검증 성공",
                                    value = """
                                            {
                                              "code": "SUCCESS",
                                              "message": "요청이 성공했습니다.",
                                              "data": {
                                                "verified": true,
                                                "message": "송금 내역의 수취 계좌가 신고 대상과 일치합니다.",
                                                "evidenceToken": "550e8400-e29b-41d4-a716-446655440000"
                                              }
                                            }
                                            """
                            )
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "필수 요청값 누락, 잘못된 계좌 선택값 또는 이미지 형식 오류",
                    content = @Content(
                            mediaType = "application/json",

                            examples = {
                                    @ExampleObject(
                                            name = "계좌 선택 필요",
                                            value = """
                        {
                          "message": "판독 결과에서 신고할 계좌를 선택해 주세요."
                        }
                        """
                                    ),
                                    @ExampleObject(
                                            name = "잘못된 계좌 선택",
                                            value = """
                        {
                          "message": "판독 결과에서 신고할 계좌를 다시 선택해 주세요."
                        }
                        """
                                    ),
                                    @ExampleObject(
                                            name = "마스킹된 계좌 입력",
                                            value = """
                        {
                          "message": "가려진 표시값으로 계좌를 비교할 수 없어요. account 대신 accountIndex로 선택해 주세요."
                        }
                        """
                                    ),
                                    @ExampleObject(
                                            name = "이미지 누락",
                                            value = """
                        {
                          "message": "송금 사진을 첨부해 주세요."
                        }
                        """
                                    )
                            }

                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "로그인 필요 또는 인증 정보 불일치",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "로그인 필요",
                                    value = """
                                            {
                                              "message": "로그인이 필요합니다."
                                            }
                                            """
                            )
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "JD 판독 결과가 없거나 세션 만료",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "판독 세션 없음",
                                    value = """
                                            {
                                              "message": "판독 세션이 끝났어요. 다시 판독해 주세요"
                                            }
                                            """
                            )
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "송금 증빙 검증 실패 또는 신고 대상 계좌 불일치",
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(
                                            name = "수취 계좌 불일치",
                                            value = """
                                                    {
                                                      "message": "송금 내역을 확인하지 못했어요. 신고 계좌와 송금 사진을 확인해 주세요."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "개인정보 분리 실패",
                                            value = """
                                                    {
                                                      "message": "송금 사진에서 개인정보를 안전하게 분리하지 못했어요. 다른 사진으로 다시 시도해 주세요."
                                                    }
                                                    """
                                    )
                            }
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "Supabase Storage 업로드 또는 저장 처리 장애",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "Storage 오류",
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
    @PostMapping(
            value = "/evidence",
            consumes = "multipart/form-data"
    )
    public ResponseEntity<ApiResponse<ReportEvidenceResponse>> verifyEvidence(

            Authentication authentication,

            HttpServletRequest httpRequest,

            @Parameter(
                    description = "JD 판독 API에서 발급한 analysisId",
                    required = true,
                    example = "89ee4f8f-c83c-4038-a2a9-403e155b20e4",
                    schema = @Schema(
                            type = "string",
                            format = "uuid",
                            requiredMode = Schema.RequiredMode.REQUIRED
                    )
            )
            @RequestParam("analysisId")
            UUID analysisId,

            @Parameter(
                    description = """
                            신고 대상 계좌의 전체 값.
                            은행명과 계좌번호를 함께 입력합니다.
                            accountIndex를 사용하는 경우 생략할 수 있습니다.
                            """,
                    example = "신한 111-111-1-111111"
            )
            @RequestParam(
                    value = "account",
                    required = false
            )
            String account,

            @Parameter(
                    description = """
                            JD 판독 결과의 계좌 목록에서 선택할 인덱스.
                            실제 인덱스 범위와 선택 규칙은
                            ReportEvidenceService의 검증 정책을 따릅니다.
                            """,
                    example = "0"
            )
            @RequestParam(
                    value = "accountIndex",
                    required = false
            )
            Integer accountIndex,

            @Parameter(
                    description = """
                            송금 완료 화면 이미지.
                            JPEG 또는 PNG, 최대 5MiB,
                            최대 40,000,000픽셀
                            """,
                    required = true,
                    schema = @Schema(
                            type = "string",
                            format = "binary"
                    )
            )
            @RequestPart("file")
            MultipartFile file
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()) {
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
                reportEvidenceService.verifySelected(
                        userId,
                        analysisId,
                        accountIndex,
                        account,
                        file,
                        httpRequest.getSession(false)
                );

        return ResponseEntity.ok(
                ApiResponse.success(result)
        );
    }
}
