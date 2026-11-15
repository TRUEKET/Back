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

    @Operation(summary = "트루샷 요청 생성", description = "제목은 최대 20자입니다. null 또는 공백이면 기본 제목을 사용합니다. 판매자에게 sellerPath를 전달하세요.")
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

    @Operation(summary = "내 트루샷 요청 목록 조회", description = "본인의 요청을 생성일 내림차순으로 반환합니다.")
    @ApiResponse(responseCode = "200", description = "요청 목록 조회 완료")
    @GetMapping
    public ResponseEntity<List<RequestSummary>> list(@Parameter(hidden = true) Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(Long.valueOf(authentication.getName())));
    }

    @Operation(summary = "트루샷 요청 상세 조회", description = "본인의 요청만 조회할 수 있습니다. 인증 성공 및 판매자 공유 동의 후 보관 기간 내에 사진 링크를 반환합니다.")
    @ApiResponse(responseCode = "200", description = "요청 상세 조회 완료")
    @ApiResponse(responseCode = "404", description = "요청이 없거나 다른 구매자의 요청", content = @Content)
    @GetMapping("/{id}")
    public ResponseEntity<RequestDetail> detail(@Parameter(hidden = true) Authentication authentication, @Parameter(description = "트루샷 요청 ID", example = "1") @PathVariable Long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.detail(Long.valueOf(authentication.getName()), id));
    }

    @Operation(summary = "공유된 마스킹 사진 조회", description = "인증 성공 및 판매자 공유 동의가 완료된 본인의 요청에 한해 JPEG 사진을 반환합니다.")
    @ApiResponse(responseCode = "200", description = "마스킹된 JPEG 사진", content = @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", description = "사진 없음, 미공유 또는 보관 기간 만료", content = @Content)
    @GetMapping(value="/{id}/photos/{number}",produces=org.springframework.http.MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> photo(@Parameter(hidden = true) Authentication authentication,@Parameter(description = "트루샷 요청 ID", example = "1") @PathVariable Long id,@Parameter(description = "사진 번호", schema = @Schema(allowableValues = {"1", "2"})) @PathVariable short number) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Content-Disposition","inline; filename=\"trueshot-"+number+".jpg\"")
                .body(service.sharedPhoto(Long.valueOf(authentication.getName()),id,number));
    }
}
