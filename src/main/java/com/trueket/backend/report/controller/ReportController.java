
package com.trueket.backend.report.controller;

import com.trueket.backend.global.response.ApiResponse;
import com.trueket.backend.report.dto.ReportDetailResponse;
import com.trueket.backend.report.dto.ReportListResponse;
import com.trueket.backend.report.service.ReportService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(
        name = "RP-06·07 내 신고 관리",
        description = "로그인 사용자의 신고 목록·상세 조회 및 신고 삭제 API"
)
@RestController
@RequestMapping("/me/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    // =====================================================
    // RP-06-01. 내 신고 목록 조회
    // =====================================================

    @Operation(
            summary = "내 신고 목록 조회",
            description = """
                    로그인한 사용자가 등록한 신고·제보 목록을 조회합니다.

                    [인증]
                    - 로그인 필요
                    - 본인이 등록한 신고만 조회

                    [페이지네이션]
                    - page: 페이지 번호 (0부터 시작)
                    - size: 페이지당 조회할 신고 수
                    - Spring Data Pageable 사용

                    [조회 결과]
                    - 신고 ID
                    - 신고 종류(FRAUD / SUSPICION)
                    - 신고 상태
                    - 거래 유형
                    - 신고 등록 일시
                    - 연결된 이의제기 알림 목록
                    - 본인 신고 식별자 배열(identifiers), 전체 표시는 value 사용
                    - Cache-Control: no-store

                    신고 목록은 생성 일시 기준 최신순으로 조회합니다.

                    이의제기가 연결되어 있다면 disputes 배열에
                    RP-08 관련 알림 정보가 포함됩니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "내 신고 목록 조회 성공",
                    content = @Content(
                            schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = com.trueket.backend.report.dto.MyReportApiSchemas.ListResponse.class),
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "목록 조회 성공",
                                    value = """
                                            {
                                              "code": "SUCCESS",
                                              "message": "요청이 성공했습니다.",
                                              "data": {
                                                "content": [
                                                  {
                                                    "reportId": 365,
                                                    "kind": "FRAUD",
                                                    "status": "PUBLISHED",
                                                    "tradeType": "TRANSFER",
                                                    "createdAt": "2026-11-15T20:00:00+09:00",
                                                    "disputes": [],
                                                    "identifiers": [{"identifierId":308,"type":"ACCOUNT","value":"신한 111111111111","maskedValue":"신한 111-111-******"}]
                                                  },
                                                  {
                                                    "reportId": 364,
                                                    "kind": "SUSPICION",
                                                    "status": "PUBLISHED",
                                                    "tradeType": "AOM",
                                                    "createdAt": "2026-11-14T18:30:00+09:00",
                                                    "disputes": [],
                                                    "identifiers": [{"identifierId":308,"type":"ACCOUNT","value":"신한 111111111111","maskedValue":"신한 111-111-******"}]
                                                  }
                                                ],
                                                "pageable": {
                                                  "pageNumber": 0,
                                                  "pageSize": 20,
                                                  "offset": 0,
                                                  "paged": true,
                                                  "unpaged": false,
                                                  "sort": {
                                                    "empty": true,
                                                    "sorted": false,
                                                    "unsorted": true
                                                  }
                                                },
                                                "totalElements": 2,
                                                "totalPages": 1,
                                                "last": true,
                                                "first": true,
                                                "size": 20,
                                                "number": 0,
                                                "numberOfElements": 2,
                                                "empty": false,
                                                "sort": {
                                                  "empty": true,
                                                  "sorted": false,
                                                  "unsorted": true
                                                }
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "로그인 필요",
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
                    responseCode = "400",
                    description = "잘못된 페이지네이션 요청",
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
            )
    })
    @GetMapping
    public ResponseEntity<ApiResponse<Page<ReportListResponse>>> getMyReports(
            Authentication authentication,
            @ParameterObject Pageable pageable
    ) {

        Long userId = Long.valueOf(authentication.getName());

        Page<ReportListResponse> reports =
                reportService.getMyReports(userId, pageable);

        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(reports));
    }

    // =====================================================
    // RP-06-02. 내 신고 상세 조회
    // =====================================================

    @Operation(
            summary = "내 신고 상세 조회",
            description = """
                로그인 사용자가 등록한 특정 신고의
                상세 정보를 조회합니다.

                [인증]
                - 로그인 필요
                - 본인이 작성한 신고만 조회 가능

                [조회 결과]
                - 신고 ID
                - 신고 종류
                - 신고 상태
                - 거래 유형
                - JD 판독 근거 문장
                - 주요 대화 상세 정보
                - 참고 링크 목록
                - 송금 날짜
                - 신고 등록 일시
                - 연결된 식별자 목록
                - 연결된 이의제기 상세 정보

                [주요 대화 - evidenceDetails]
                - imageIndex: 사진 번호 (1부터 시작)
                - signalId: 사기 판단 신호 ID
                - quote: 마스킹된 근거 문장
                - severity: STRONG / CAUTION
                - action: 의심 행동 유형
                - reason: 판단 이유
                - informational: 위험도 계산에서 제외된 참고 신호 여부
                - cases: 유사 피해 사례 및 기사 링크

                [실제 피해 사례]
                - summary: 피해 사례 요약
                - source: 기사 출처
                - publishedAt: 기사 발행 날짜
                - url: 기사 원문 링크
                - 등록된 사례가 없으면 빈 배열 반환
                - 해당 신고의 실제 피해 사실을 의미하지 않음

                [개인정보]
                - 본인 신고 식별자의 전체 표시는 identifiers[].value 사용
                - maskedValue는 기존 클라이언트 호환용 마스킹 값
                - 전체 값 응답은 Cache-Control: no-store 적용
                - 숨김 처리된 식별자는 제외
                - 대화 원본 사진은 반환하지 않음
                - 판매자 이메일은 반환하지 않음

                [이의제기 - disputes]
                - 기존 이의제기 알림 정보 포함
                - description: 판매자 추가 설명
                - proofImages: 마스킹된 증빙 사진 목록
                - 전달 완료된 정상 거래·환불 이의제기만
                  판매자 설명 및 증빙 사진 제공
                - 이의제기가 없으면 빈 배열 반환

                [증빙 사진 유형]
                - DELIVERY: 티켓 전달 완료 증빙
                - TRANSFER: 송금 내역 증빙
                - REFUND: 환불 완료 증빙

                [Signed URL]
                - 증빙 사진 URL은 발급 후 300초 동안 유효
                - 만료 시 상세 조회 API를 다시 요청
                - URL을 별도로 저장하거나 외부에 공유하지 않음

                [기존 신고 호환성]
                - evidenceQuotes는 기존과 동일하게 제공
                - 상세 정보가 없는 기존 신고는
                  evidenceDetails에 빈 배열 반환

                신고가 없거나 본인 신고가 아닌 경우
                REPORT_NOT_FOUND 오류가 발생합니다.
                """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "내 신고 상세 조회 성공",
                    content = @Content(
                            schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = com.trueket.backend.report.dto.MyReportApiSchemas.DetailResponse.class),
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(
                                            name = "주요 대화 및 이의제기 포함",
                                            value = """
                                                {
                                                  "code": "SUCCESS",
                                                  "message": "요청이 성공했습니다.",
                                                  "data": {
                                                    "reportId": 365,
                                                    "kind": "FRAUD",
                                                    "status": "RESPONDING",
                                                    "tradeType": "TRANSFER",
                                                    "evidenceQuotes": [
                                                      "본명 말고 닉네임으로 입금해주세요."
                                                    ],
                                                    "evidenceDetails": [
                                                      {
                                                        "imageIndex": 1,
                                                        "signalId": "S-B3",
                                                        "quote": "본명 말고 닉네임으로 입금해주세요.",
                                                        "severity": "CAUTION",
                                                        "action": "수상한 송금 요구",
                                                        "reason": "입금자 확인을 피하려는 말입니다.",
                                                        "informational": false,
                                                        "cases": [
                                                          {
                                                            "signalId": "S-B3",
                                                            "summary": "닉네임 입금과 2차 송금 요구로 36만원 피해 발생",
                                                            "source": "아시아경제",
                                                            "publishedAt": "2026-02-09",
                                                            "url": "https://view.asiae.co.kr/visual-news/article/2026020914165800654"
                                                          }
                                                        ]
                                                      }
                                                    ],
                                                    "referenceLinks": [],
                                                    "sentDate": "2026-11-15",
                                                    "createdAt": "2026-11-15T20:00:00+09:00",
                                                    "identifiers": [
                                                      {
                                                        "identifierId": 308,
                                                        "type": "ACCOUNT",
                                                        "maskedValue": "신한 111-111-******",
                                                        "value": "신한 111111111111"
                                                      }
                                                    ],
                                                    "disputes": [
                                                      {
                                                        "disputeId": 19,
                                                        "reason": "REFUND",
                                                        "status": "RESPONDING",
                                                        "deadline": "2026-11-21T10:13:59Z",
                                                        "canRespond": true,
                                                        "canRepublish": false,
                                                        "description": "구매자에게 전액 환불을 완료했습니다.",
                                                        "proofImages": [
                                                          {
                                                            "type": "REFUND",
                                                            "imageUrl": "https://example.supabase.co/storage/v1/object/sign/dispute-evidence/19/example.png?token=example"
                                                          }
                                                        ]
                                                      }
                                                    ]
                                                  }
                                                }
                                                """
                                    ),
                                    @ExampleObject(
                                            name = "기존 신고 또는 상세 정보 없음",
                                            value = """
                                                {
                                                  "code": "SUCCESS",
                                                  "message": "요청이 성공했습니다.",
                                                  "data": {
                                                    "reportId": 364,
                                                    "kind": "SUSPICION",
                                                    "status": "PUBLISHED",
                                                    "tradeType": "TRANSFER",
                                                    "evidenceQuotes": [
                                                      "지금 바로 입금해야 해요."
                                                    ],
                                                    "evidenceDetails": [],
                                                    "referenceLinks": [],
                                                    "sentDate": null,
                                                    "createdAt": "2026-11-14T18:30:00+09:00",
                                                    "identifiers": [
                                                      {
                                                        "identifierId": 308,
                                                        "type": "ACCOUNT",
                                                        "maskedValue": "신한 111-111-******",
                                                        "value": "신한 111111111111"
                                                      }
                                                    ],
                                                    "disputes": []
                                                  }
                                                }
                                                """
                                    )
                            }
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "로그인 필요",
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
                    responseCode = "404",
                    description = "신고가 존재하지 않거나 본인 신고가 아님",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                        {
                                          "code": "REPORT_NOT_FOUND",
                                          "message": "신고 기록을 찾을 수 없습니다.",
                                          "data": null
                                        }
                                        """
                            )
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "증빙 사진 Signed URL 발급 실패",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                        {
                                          "message": "이의제기 증빙 사진을 조회하지 못했습니다."
                                        }
                                        """
                            )
                    )
            )
    })
    @GetMapping("/{reportId}")
    public ResponseEntity<ApiResponse<ReportDetailResponse>> getMyReportDetail(
            Authentication authentication,

            @Parameter(
                    description = "조회할 신고 ID",
                    example = "365",
                    required = true
            )
            @PathVariable Long reportId
    ) {
        Long userId = Long.valueOf(authentication.getName());

        ReportDetailResponse report =
                reportService.getMyReportDetail(userId, reportId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(report));
    }


    // =====================================================
    // RP-07. 내 신고 삭제
    // =====================================================

    @Operation(
            summary = "내 신고 삭제",
            description = """
                    로그인한 사용자가 본인이 작성한 신고·제보를 삭제합니다.

                    [인증]
                    - 로그인 필요
                    - 본인이 작성한 신고만 삭제 가능

                    [삭제 처리]
                    1. 신고 소유권 확인
                    2. 연결된 증빙 파일의 Storage 경로 조회
                    3. 이의제기 삭제 이력 보존
                    4. Supabase Storage 실제 증빙 파일 삭제 요청
                    5. 이의제기 연결 데이터 삭제
                    6. 증빙 DB 데이터 삭제
                    7. 신고-식별자 연결 데이터 삭제
                    8. 신고 원본 삭제
                    
                    [주의사항]
                    - Storage 삭제 실패 시 HTTP 503
                    - Storage 삭제와 DB 트랜잭션은 원자적으로 처리되지 않음
                    - Storage 삭제 성공 후 DB 작업 실패 시 부분 삭제 가능성 존재

                    [삭제 정책]
                    - 신고 원본은 DB에서 삭제됩니다.
                    - 삭제된 신고는 내 신고 목록에서 제외됩니다.
                    - 삭제된 신고는 신고 리스트 집계에서도 제외됩니다.
                    - 연결된 식별자 자체는 삭제하지 않습니다.
                    - 이의제기 삭제 이력은 별도 테이블에 보존합니다.
                    - 신고 삭제 시 연결된 증빙 이미지의 Storage 경로를 조회하고, Supabase Storage에서 실제 파일 삭제를 요청합니다.
                    - Storage 삭제가 실패하면 API는 오류를 반환합니다.

                    [응답]
                    - 삭제 성공: HTTP 200
                    - data는 null
                    - 신고가 없거나 본인 신고가 아닌 경우: HTTP 404
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "신고 삭제 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "삭제 성공",
                                    value = """
                                            {
                                              "code": "SUCCESS",
                                              "message": "요청이 성공했습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "로그인하지 않았거나 인증 정보가 유효하지 않음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "로그인 필요",
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
                    responseCode = "404",
                    description = "신고가 존재하지 않거나 본인이 작성한 신고가 아님",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "신고를 찾을 수 없음",
                                    value = """
                                            {
                                              "code": "REPORT_NOT_FOUND",
                                              "message": "신고 기록을 찾을 수 없습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "신고 삭제 처리 중 서버 내부 오류",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "서버 오류",
                                    value = """
                                            {
                                              "code": "INTERNAL_SERVER_ERROR",
                                              "message": "서버 내부 오류가 발생했습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    )
            ),

            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "Supabase Storage 증빙 이미지 삭제 실패",
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
            )

    })
    @DeleteMapping("/{reportId}")
    public ResponseEntity<ApiResponse<Void>> deleteMyReport(
            Authentication authentication,

            @Parameter(
                    description = "삭제할 신고 ID",
                    example = "365",
                    required = true
            )
            @PathVariable Long reportId
    ) {

        Long userId = Long.valueOf(authentication.getName());

        reportService.deleteMyReport(userId, reportId);

        return ResponseEntity.ok(
                ApiResponse.success(null)
        );
    }

}
