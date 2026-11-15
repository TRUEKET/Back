
package com.trueket.backend.lookup.controller;

import com.trueket.backend.global.response.ApiResponse;
import com.trueket.backend.lookup.dto.LookupCompareRequest;
import com.trueket.backend.lookup.dto.LookupCompareResponse;
import com.trueket.backend.lookup.dto.LookupDetailResponse;
import com.trueket.backend.lookup.dto.LookupResponse;
import com.trueket.backend.lookup.service.LookupService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(
        name = "RP-01~03 신고 리스트 조회",
        description = "계좌·전화번호·SNS 식별자 조회, 상세 정보 및 전체 값 비교 API"
)
@RestController
@RequestMapping("/lookup")
@RequiredArgsConstructor
public class LookupController {

    private final LookupService lookupService;

    // =====================================================
    // RP-01. 신고 리스트 조회
    // =====================================================

    @Operation(
            summary = "신고 리스트 조회",
            description = """
                    계좌번호, 전화번호 또는 X 계정을 검색하여
                    신고 리스트 기준 위험도를 조회합니다.

                    [인증]
                    - 로그인 불필요

                    [검색]
                    - 입력값의 종류를 자동 판별
                    - 정규화 후 해시로 일치하는 식별자 조회
                    - 조회 대상이 존재하지 않으면 SAFE 반환

                    [신고 집계]
                    - 최근 1년간 유효한 신고
                    - PUBLISHED, RESPONDING, REVIEWING 상태 포함
                    - PRIVATE 상태 제외
                    - 동일 휴대폰 해시의 신고자는 중복 제거

                    [위험도]
                    - DANGER: 피해 신고자 1명 이상
                    - SUSPICION: 피해 신고자 0명, 의심 제보자 2명 이상
                    - SAFE: 그 외

                    검색 결과가 없으면 identifierId와 value는 null,
                    fraudCount와 suspicionCount는 0입니다.
                    
                    [요청 제한]
                    - 로그인 여부와 관계없이 IP별 1분 최대 30회
                    - RP-01, RP-02, RP-03 요청 횟수 합산
                    - 31번째 요청부터 HTTP 429 반환
                    - 제한 시간은 최초 요청부터 1분
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "신고 리스트 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(
                                            name = "위험",
                                            value = """
                                                    {
                                                      "code": "SUCCESS",
                                                      "message": "요청이 성공했습니다.",
                                                      "data": {
                                                        "identifierId": 308,
                                                        "type": "ACCOUNT",
                                                        "value": "SHINHAN:111111111111",
                                                        "riskLevel": "DANGER",
                                                        "fraudCount": 1,
                                                        "suspicionCount": 2
                                                      }
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "의심",
                                            value = """
                                                    {
                                                      "code": "SUCCESS",
                                                      "message": "요청이 성공했습니다.",
                                                      "data": {
                                                        "identifierId": 309,
                                                        "type": "ACCOUNT",
                                                        "value": "SHINHAN:222222222222",
                                                        "riskLevel": "SUSPICION",
                                                        "fraudCount": 0,
                                                        "suspicionCount": 2
                                                      }
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "검색 결과 없음",
                                            value = """
                                                    {
                                                      "code": "SUCCESS",
                                                      "message": "요청이 성공했습니다.",
                                                      "data": {
                                                        "identifierId": null,
                                                        "type": "ACCOUNT",
                                                        "value": null,
                                                        "riskLevel": "SAFE",
                                                        "fraudCount": 0,
                                                        "suspicionCount": 0
                                                      }
                                                    }
                                                    """
                                    )
                            }
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "올바르지 않은 계좌번호, 전화번호 또는 SNS 식별자",
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(
                                            name = "잘못된 식별자",
                                            value = """
                                        {
                                          "code": "INVALID_REQUEST",
                                          "message": "잘못된 요청 값입니다.",
                                          "data": null
                                        }
                                        """
                                    ),
                                    @ExampleObject(
                                            name = "필수 파라미터 누락",
                                            value = """
                                        {
                                          "code": "INVALID_REQUEST",
                                          "message": "필수 요청 파라미터가 누락되었습니다: q",
                                          "data": null
                                        }
                                        """
                                    )
                            }
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429",
                    description = "IP별 요청 횟수 제한 초과 (1분에 최대 30회)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "요청 횟수 제한 초과",
                                    value = """
                                {
                                  "code": "TOO_MANY_REQUESTS",
                                  "message": "조회 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
                                  "data": null
                                }
                                """
                            )
                    )
            )

    })
    @GetMapping
    public ResponseEntity<ApiResponse<LookupResponse>> lookup(

            @Parameter(
                    description = "조회할 은행명+계좌번호, 전화번호 또는 X 계정",
                    example = "신한 111-111-1-111111",
                    required = true
            )
            @RequestParam("q") String query
    ) {
        LookupResponse response = lookupService.lookup(query);

        return ResponseEntity.ok(
                ApiResponse.success(response)
        );
    }

    // =====================================================
    // RP-02. 신고 리스트 상세 조회
    // =====================================================

    @Operation(
            summary = "신고 리스트 상세 조회",
            description = """
                    특정 식별자의 신고 현황과 연관 정보를 조회합니다.

                    [인증]
                    - 로그인 불필요

                    [조회 정보]
                    - 식별자 ID, 종류, 마스킹 값
                    - 위험도
                    - 피해 신고자 수
                    - 의심 제보자 수
                    - 연관 식별자 목록
                    - 신고 타임라인

                    [집계 조건]
                    - 최근 1년간 유효한 신고만 조회
                    - 동일 휴대폰 해시 중복 제거
                    - PRIVATE 상태 제외
                    - 연관 식별자는 서로 다른 신고자 2명 이상과
                      같은 신고에 등장한 식별자만 반환

                    [타임라인]
                    - 신고 등록 날짜 및 신고 종류 반환
                    - 최신 등록순 정렬

                    존재하지 않는 identifierId는 HTTP 404를 반환합니다.
                    
                    [요청 제한]
                    - 로그인 여부와 관계없이 IP별 1분 최대 30회
                    - RP-01, RP-02, RP-03 요청 횟수 합산
                    - 31번째 요청부터 HTTP 429 반환
                    - 제한 시간은 최초 요청부터 1분
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "신고 리스트 상세 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "상세 조회 성공",
                                    value = """
                                            {
                                              "code": "SUCCESS",
                                              "message": "요청이 성공했습니다.",
                                              "data": {
                                                "identifierId": 308,
                                                "type": "ACCOUNT",
                                                "maskedValue": "신한 111-111-******",
                                                "riskLevel": "DANGER",
                                                "fraudCount": 1,
                                                "suspicionCount": 2,
                                                "relatedIdentifiers": [
                                                  {
                                                    "identifierId": 309,
                                                    "type": "PHONE",
                                                    "maskedValue": "010-****-1234"
                                                  }
                                                ],
                                                "timeline": [
                                                  {
                                                    "date": "2026-11-15",
                                                    "kind": "FRAUD"
                                                  },
                                                  {
                                                    "date": "2026-11-14",
                                                    "kind": "SUSPICION"
                                                  }
                                                ]
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "해당 식별자를 찾을 수 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "code": "IDENTIFIER_NOT_FOUND",
                                              "message": "조회 대상 정보를 찾을 수 없습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429",
                    description = "IP별 조회 요청 횟수 제한 초과 (RP-01~03 합산 1분 최대 30회)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "요청 횟수 제한 초과",
                                    value = """
                                {
                                  "code": "TOO_MANY_REQUESTS",
                                  "message": "조회 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
                                  "data": null
                                }
                                """
                            )
                    )
            )

    })
    @GetMapping("/{identifierId}")
    public ResponseEntity<ApiResponse<LookupDetailResponse>> getLookupDetail(

            @Parameter(
                    description = "상세 조회할 식별자 ID",
                    example = "308",
                    required = true
            )
            @PathVariable Long identifierId
    ) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        lookupService.getLookupDetail(identifierId)
                )
        );
    }

    // =====================================================
    // RP-03. 식별자 전체 값 비교
    // =====================================================

    @Operation(
            summary = "식별자 전체 값 비교",
            description = """
                    사용자가 알고 있는 전체 식별자 값을
                    신고 리스트에 저장된 식별자와 비교합니다.

                    [인증]
                    - 로그인 불필요

                    [비교 절차]
                    1. identifierId로 기존 식별자 조회
                    2. 사용자가 입력한 값을 정규화
                    3. 식별자 종류 비교
                    4. 정규화된 입력값의 해시 생성
                    5. DB에 저장된 해시와 비교

                    [비교 결과]
                    - 일치: matched=true, 정규화된 입력값 반환
                    - 불일치: matched=false, value=null

                    [개인정보]
                    - 입력값을 신고 DB에 저장하지 않음
                    - 저장된 암호문을 복호화하지 않음
                    - 일치했을 때 사용자 입력의 정규화 값을 반환
                    
                    [요청 제한]
                    - 로그인 여부와 관계없이 IP별 1분 최대 30회
                    - RP-01, RP-02, RP-03 요청 횟수 합산
                    - 31번째 요청부터 HTTP 429 반환
                    - 제한 시간은 최초 요청부터 1분
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "식별자 비교 완료 (일치 또는 불일치)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(
                                            name = "일치",
                                            value = """
                                                    {
                                                      "code": "SUCCESS",
                                                      "message": "요청이 성공했습니다.",
                                                      "data": {
                                                        "matched": true,
                                                        "value": "SHINHAN:111111111111"
                                                      }
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "불일치",
                                            value = """
                                                    {
                                                      "code": "SUCCESS",
                                                      "message": "요청이 성공했습니다.",
                                                      "data": {
                                                        "matched": false,
                                                        "value": null
                                                      }
                                                    }
                                                    """
                                    )
                            }
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "비교값 누락 또는 올바르지 않은 식별자 입력",
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(
                                            name = "비교값 누락 또는 공백",
                                            description = "@NotBlank 유효성 검사 실패",
                                            value = """
                                        {
                                          "message": "비교할 값을 입력해 주세요."
                                        }
                                        """
                                    ),
                                    @ExampleObject(
                                            name = "잘못된 식별자 형식",
                                            description = "IdentifierNormalizer 정규화 실패",
                                            value = """
                                        {
                                          "code": "INVALID_REQUEST",
                                          "message": "잘못된 요청 값입니다.",
                                          "data": null
                                        }
                                        """
                                    )
                            }
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "비교 대상 식별자가 존재하지 않음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "code": "IDENTIFIER_NOT_FOUND",
                                              "message": "조회 대상 정보를 찾을 수 없습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429",
                    description = "IP별 조회 요청 횟수 제한 초과 (RP-01~03 합산 1분 최대 30회)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "요청 횟수 제한 초과",
                                    value = """
                                {
                                  "code": "TOO_MANY_REQUESTS",
                                  "message": "조회 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
                                  "data": null
                                }
                                """
                            )
                    )
            )

    })
    @PostMapping("/{identifierId}/compare")
    public ResponseEntity<ApiResponse<LookupCompareResponse>> compare(

            @Parameter(
                    description = "비교 대상 식별자 ID",
                    example = "308",
                    required = true
            )
            @PathVariable Long identifierId,

            @Valid @RequestBody LookupCompareRequest request
    ) {
        LookupCompareResponse response = lookupService.compare(
                identifierId,
                request.value()
        );

        return ResponseEntity.ok(
                ApiResponse.success(response)
        );
    }
}
