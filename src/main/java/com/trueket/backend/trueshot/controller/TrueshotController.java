package com.trueket.backend.trueshot.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.trueket.backend.trueshot.dto.TrueshotDtos.CreateRequest;
import com.trueket.backend.trueshot.dto.TrueshotDtos.RequestDetail;
import com.trueket.backend.trueshot.dto.TrueshotDtos.RequestSummary;
import com.trueket.backend.trueshot.dto.TrueshotDtos.UpdateTitleRequest;
import com.trueket.backend.trueshot.service.TrueshotRequestService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "트루샷 구매자", description = "로그인한 구매자의 인증 요청 및 공유 사진 조회")
@SecurityRequirement(name = "sessionAuth")
@ApiResponse(responseCode = "401", description = "로그인이 필요하거나 비활성 사용자", content = @Content)
@ApiResponse(responseCode = "403", description = "접근 권한이 없거나 CSRF 토큰 누락·불일치", content = @Content)
@RestController
@RequestMapping(value = "/trueshot", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
public class TrueshotController {
    private final TrueshotRequestService service;

    public TrueshotController(TrueshotRequestService service) {
        this.service = service;
    }

    @Operation(summary = "트루샷 요청 생성", description = "구매자가 인증 요청을 만들 때 호출합니다. 응답의 sellerPath를 판매자에게 전달합니다.")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true, schema = @Schema(type = "string"),
            description = "같은 브라우저에서 GET /auth/csrf로 발급받은 token 값. 로그인 후 재발급하세요.")
    @ApiResponse(responseCode = "201", description = "인증 요청 생성 완료")
    @ApiResponse(responseCode = "400", description = "제목 길이 초과 또는 잘못된 요청", content = @Content)
    @PostMapping
    public ResponseEntity<RequestDetail> create(@Parameter(hidden = true) Authentication authentication,
                                                @Valid @RequestBody CreateRequest input) {
        RequestDetail result = service.create(Long.valueOf(authentication.getName()), input.title());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(result);
    }


    @Operation(
            summary = "내 트루샷 요청 목록 조회",
            description = """
                구매자가 트루샷 요청 목록 화면에 들어오거나
                목록을 새로고침할 때 호출합니다.

                [요청 상태]
                - PENDING: 촬영 대기
                - IN_PROGRESS: 인증 진행 중
                - PASSED: 인증 성공
                - FAILED: 인증 실패 또는 시간 만료

                [실패 코드 - failureCode]
                - REQUEST_TIMEOUT: 전체 요청 시간 만료
                - FIRST_PHOTO_TIMEOUT: 첫 번째 사진 제출 시간 초과
                - SECOND_PHOTO_TIMEOUT: 두 번째 사진 제출 시간 초과
                - ANALYSIS_TIMEOUT: 사진 분석 시간 초과
                - VERIFICATION_FAILED: 사진 인증 실패
                - null: 실패하지 않은 상태

                시간 초과로 인한 실패와 일반 인증 실패를
                failureCode를 통해 구분할 수 있습니다.

                photosSharedAt은 판매자가 인증 사진을 공유한 시각입니다.
                공유 대기 중이거나 공유하지 않고 종료한 경우 null입니다.
                phase가 COMPLETED이고 photosSharedAt이 null이면 사진 미공유 종료입니다.
                """
    )
    @ApiResponse(responseCode = "200", description = "요청 목록 조회 완료")
    @GetMapping
    public ResponseEntity<List<RequestSummary>> list(@Parameter(hidden = true) Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(Long.valueOf(authentication.getName())));
    }

    @Operation(summary = "트루샷 요청 상세 조회", description = "구매자가 요청 상세 화면에서 진행 상황과 인증 결과를 확인할 때 호출합니다.")
    @ApiResponse(responseCode = "200", description = "요청 상세 조회 완료")
    @ApiResponse(responseCode = "404", description = "요청이 없거나 다른 구매자의 요청", content = @Content)
    @GetMapping("/{id}")
    public ResponseEntity<RequestDetail> detail(@Parameter(hidden = true) Authentication authentication, @Parameter(description = "트루샷 요청 ID", example = "1") @PathVariable Long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.detail(Long.valueOf(authentication.getName()), id));
    }

    @Operation(summary = "트루샷 요청 제목 수정", description = "로그인한 구매자 본인의 요청 제목을 수정합니다. 제목은 필수이며 최대 20자, 공백만 입력할 수 없습니다. 앞뒤 공백은 제거합니다. 인증 진행·완료·만료 상태에서도 수정할 수 있으며 수정된 요청 상세를 반환합니다.")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true, schema = @Schema(type = "string"),
            description = "같은 브라우저에서 GET /auth/csrf로 발급받은 token 값. 로그인 후 재발급하세요.")
    @ApiResponse(responseCode = "200", description = "제목 수정 완료, GET 상세와 같은 응답")
    @ApiResponse(responseCode = "400", description = "제목 누락·공백 또는 20자 초과", content = @Content)
    @ApiResponse(responseCode = "404", description = "요청이 없거나 다른 구매자의 요청", content = @Content)
    @PatchMapping("/{id}")
    public ResponseEntity<RequestDetail> updateTitle(@Parameter(hidden = true) Authentication authentication,
            @Parameter(description = "트루샷 요청 ID", example = "1") @PathVariable Long id,
            @Valid @RequestBody UpdateTitleRequest input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.updateTitle(Long.valueOf(authentication.getName()), id, input.title()));
    }

    @Operation(summary = "공유된 마스킹 사진 조회", description = "구매자가 요청 상세 응답의 photos에 포함된 공유 사진을 표시할 때 호출합니다.")
    @ApiResponse(responseCode = "200", description = "마스킹된 JPEG 사진", content = @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", description = "사진 없음, 미공유 또는 보관 기간 만료", content = @Content)
    @GetMapping(value="/{id}/photos/{number}",produces=org.springframework.http.MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> photo(@Parameter(hidden = true) Authentication authentication,@Parameter(description = "트루샷 요청 ID", example = "1") @PathVariable Long id,@Parameter(description = "사진 번호", schema = @Schema(allowableValues = {"1", "2"})) @PathVariable short number) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Content-Disposition","inline; filename=\"trueshot-"+number+".jpg\"")
                .body(service.sharedPhoto(Long.valueOf(authentication.getName()),id,number));
    }
}
