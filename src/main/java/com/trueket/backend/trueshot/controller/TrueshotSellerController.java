package com.trueket.backend.trueshot.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.trueket.backend.trueshot.dto.TrueshotDtos.SellerLink;
import com.trueket.backend.trueshot.dto.TrueshotDtos.SellerStart;
import com.trueket.backend.trueshot.dto.TrueshotDtos.SellerStartRequest;
import com.trueket.backend.trueshot.service.TrueshotRequestService;
import com.trueket.backend.trueshot.service.TrueshotPhotoService;
import com.trueket.backend.trueshot.dto.TrueshotPhotoResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.trueket.backend.trueshot.service.TrueshotMaskService;
import com.trueket.backend.trueshot.dto.TrueshotMaskDtos.*;
import com.trueket.backend.global.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import com.trueket.backend.trueshot.dto.HandAnalysisDtos.Input;
import com.trueket.backend.trueshot.dto.TrueshotDtos.SellerResult;
import com.trueket.backend.trueshot.service.TrueshotJudgmentService;

@Tag(name = "트루샷 판매자", description = "로그인 불필요. /start 이후에는 인증을 시작한 브라우저의 세션을 유지해야 합니다. 모든 POST에 CSRF 토큰이 필요합니다.")
@ApiResponse(responseCode = "403", description = "인증을 시작한 세션이 아니거나 CSRF 토큰 누락·불일치", content = @Content)
@ApiResponse(responseCode = "404", description = "링크 또는 사진을 찾을 수 없음", content = @Content)
@RestController
@RequestMapping(value = "/s/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
public class TrueshotSellerController {
    private final TrueshotRequestService service;
    private final TrueshotPhotoService photos;
    private final TrueshotMaskService masks;
    private final TrueshotJudgmentService judgments;

    public TrueshotSellerController(TrueshotRequestService service, TrueshotPhotoService photos, TrueshotMaskService masks,
            TrueshotJudgmentService judgments) {
        this.service = service;
        this.photos = photos;
        this.masks = masks;
        this.judgments = judgments;
    }

    @Operation(summary = "판매자 링크 조회", description = "판매자가 전달받은 링크로 처음 접속해 인증 시작 전 안내를 확인할 때 호출합니다.")
    @ApiResponse(responseCode = "200", description = "요청 처리 완료")
    @ApiResponse(responseCode = "409", description = "이미 사용한 링크 또는 현재 단계에서 호출 불가", content = @Content)
    @ApiResponse(responseCode = "410", description = "인증 또는 촬영·공유 기한 만료", content = @Content)
    @GetMapping
    public ResponseEntity<SellerLink> link(@Parameter(description = "구매자가 전달한 sellerPath의 토큰") @PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.sellerLink(token));
    }

    @Operation(summary = "판매자 인증 시작", description = "판매자가 사진 전달에 동의하고 인증 시작 버튼을 누를 때 호출합니다.")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true, schema = @Schema(type = "string"),
            description = "같은 브라우저에서 GET /auth/csrf로 발급받은 token 값. 로그인 후 재발급하세요.")
    @ApiResponse(responseCode = "200", description = "요청 처리 완료")
    @ApiResponse(responseCode = "400", description = "요청 값 또는 사진·손 인식 데이터 오류", content = @Content)
    @ApiResponse(responseCode = "409", description = "이미 사용한 링크 또는 현재 단계에서 호출 불가", content = @Content)
    @ApiResponse(responseCode = "410", description = "인증 또는 촬영·공유 기한 만료", content = @Content)
    @PostMapping("/start")
    public ResponseEntity<SellerStart> start(@Parameter(description = "구매자가 전달한 sellerPath의 토큰") @PathVariable String token,
                                             @Valid @RequestBody SellerStartRequest input, @Parameter(hidden = true) HttpServletRequest request) {
        SellerStart result=service.start(token);
        request.getSession().setAttribute("trueshotSeller:"+token,Boolean.TRUE);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @Operation(summary = "인증 사진 업로드", description = "판매자가 촬영한 사진을 제출할 때 호출합니다. 첫 사진은 n=1, 두 번째 사진은 n=2로 보내며 두 번째 사진에는 analysis를 함께 전달합니다.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
            encoding = @Encoding(name = "analysis", contentType = MediaType.APPLICATION_JSON_VALUE)))
    @SecurityRequirement(name = "sessionAuth")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true, schema = @Schema(type = "string"),
            description = "같은 브라우저에서 GET /auth/csrf로 발급받은 token 값. 로그인 후 재발급하세요.")
    @ApiResponse(responseCode = "200", description = "요청 처리 완료")
    @ApiResponse(responseCode = "400", description = "요청 값 또는 사진·손 인식 데이터 오류", content = @Content)
    @ApiResponse(responseCode = "409", description = "이미 사용한 링크 또는 현재 단계에서 호출 불가", content = @Content)
    @ApiResponse(responseCode = "410", description = "인증 또는 촬영·공유 기한 만료", content = @Content)
    @ApiResponse(responseCode = "413", description = "사진 용량 제한 초과", content = @Content)
    @ApiResponse(responseCode = "503", description = "외부 분석 또는 저장 서비스 일시 장애", content = @Content)
    @ApiResponse(responseCode = "415", description = "multipart/form-data 또는 analysis 파트의 application/json 형식 오류", content = @Content)
    @PostMapping(value = "/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TrueshotPhotoResponse> photo(@Parameter(description = "구매자가 전달한 sellerPath의 토큰") @PathVariable String token,
            @Parameter(description = "업로드할 사진 번호", schema = @Schema(allowableValues = {"1", "2"})) @RequestParam("n") short number, @Parameter(description = "5MB 이하 JPEG 또는 PNG 사진", schema = @Schema(type = "string", format = "binary")) @RequestPart("photo") MultipartFile photo,
            @Parameter(description = "두 번째 사진에서 필수. Content-Type: application/json인 손 인식 결과", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Input.class))) @Valid @RequestPart(value="analysis",required=false) Input analysis, @Parameter(hidden = true) HttpServletRequest request) {
        requireSeller(request,token);
        var result=photos.upload(token, number, photo, analysis);
        if (number==2) {
            SellerResult judged;
            try {
                judged=judgments.judge(token);
            } catch (ApiException e) {
                // 접수는 이미 커밋했다. 외부 서비스 장애는 같은 사진으로 재시도한다.
                if (e.getStatus()!=HttpStatus.SERVICE_UNAVAILABLE) throw e;
                judged=judgments.sellerResult(token);
            }
            result=new TrueshotPhotoResponse(2,judged.phase(),true,
                    judged.phase()==com.trueket.backend.trueshot.entity.enums.TrueshotPhase.JUDGING?judged.judgmentDeadline():null,
                    java.util.List.of(),judged.checks().stream().filter(check -> check.photoNumber()==2).toList(),judged.failReasons());
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @Operation(summary = "첫 사진 개인정보 자동 마스킹", description = "첫 사진이 통과한 뒤 개인정보를 가릴 때 호출합니다. 두 번째 사진은 서버가 자동으로 가립니다.")
    @SecurityRequirement(name = "sessionAuth")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true, schema = @Schema(type = "string"),
            description = "같은 브라우저에서 GET /auth/csrf로 발급받은 token 값. 로그인 후 재발급하세요.")
    @ApiResponse(responseCode = "200", description = "요청 처리 완료")
    @ApiResponse(responseCode = "409", description = "이미 사용한 링크 또는 현재 단계에서 호출 불가", content = @Content)
    @ApiResponse(responseCode = "410", description = "인증 또는 촬영·공유 기한 만료", content = @Content)
    @ApiResponse(responseCode = "413", description = "사진 용량 제한 초과", content = @Content)
    @ApiResponse(responseCode = "503", description = "외부 분석 또는 저장 서비스 일시 장애", content = @Content)
    @PostMapping("/mask")
    public ResponseEntity<MaskResult> mask(@Parameter(description = "구매자가 전달한 sellerPath의 토큰") @PathVariable String token,@Parameter(hidden = true) HttpServletRequest request) {
        requireSeller(request,token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(masks.prepare(token));
    }

    @Operation(summary = "마스킹 사진 미리보기", description = "판매자가 첫 사진의 가림 상태를 확인하거나, 최종 공유 전에 가려진 사진을 확인할 때 호출합니다.")
    @SecurityRequirement(name = "sessionAuth")
    @ApiResponse(responseCode = "200", description = "마스킹된 JPEG 사진", content = @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "409", description = "이미 사용한 링크 또는 현재 단계에서 호출 불가", content = @Content)
    @ApiResponse(responseCode = "410", description = "인증 또는 촬영·공유 기한 만료", content = @Content)
    @GetMapping(value="/preview/{number}",produces=MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> preview(@Parameter(description = "구매자가 전달한 sellerPath의 토큰") @PathVariable String token,@Parameter(description = "사진 번호", schema = @Schema(allowableValues = {"1", "2"})) @PathVariable short number,@Parameter(hidden = true) HttpServletRequest request) {
        requireSeller(request,token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(masks.preview(token,number));
    }

    @Operation(summary = "인증 진행 상태 및 결과 조회", description = "판매자가 새로고침 후 진행 화면을 복구하거나 두 번째 사진의 판정 결과를 확인할 때 호출합니다. JUDGING이면 pollAfterMillis 간격으로 다시 조회합니다.")
    @SecurityRequirement(name = "sessionAuth")
    @ApiResponse(responseCode = "200", description = "요청 처리 완료")
    @GetMapping("/result")
    public ResponseEntity<SellerResult> result(@Parameter(description = "구매자가 전달한 sellerPath의 토큰") @PathVariable String token,@Parameter(hidden = true) HttpServletRequest request) {
        requireSeller(request,token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(judgments.sellerResult(token));
    }

    @Operation(summary = "최종 결과 및 사진 공유 확정", description = "판매자가 인증 성공 후 사진 공유 동의 또는 거절을 선택할 때 호출합니다.")
    @SecurityRequirement(name = "sessionAuth")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true, schema = @Schema(type = "string"),
            description = "같은 브라우저에서 GET /auth/csrf로 발급받은 token 값. 로그인 후 재발급하세요.")
    @ApiResponse(responseCode = "200", description = "요청 처리 완료")
    @ApiResponse(responseCode = "400", description = "요청 값 또는 사진·손 인식 데이터 오류", content = @Content)
    @ApiResponse(responseCode = "409", description = "이미 사용한 링크 또는 현재 단계에서 호출 불가", content = @Content)
    @ApiResponse(responseCode = "410", description = "인증 또는 촬영·공유 기한 만료", content = @Content)
    @ApiResponse(responseCode = "413", description = "사진 용량 제한 초과", content = @Content)
    @ApiResponse(responseCode = "503", description = "외부 분석 또는 저장 서비스 일시 장애", content = @Content)
    @PostMapping("/share")
    public ResponseEntity<SellerResult> share(@Parameter(description = "구매자가 전달한 sellerPath의 토큰") @PathVariable String token,@Valid @RequestBody SharePhotos input,
            @Parameter(hidden = true) HttpServletRequest request) {
        requireSeller(request,token);
        masks.share(token,input);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(judgments.sellerResult(token));
    }

    @Operation(summary = "첫 사진 마스킹 확인 및 두 번째 미션 발급", description = "판매자가 첫 사진의 가림 상태를 확인하고 다음 촬영으로 넘어갈 때 호출합니다. 두 번째 촬영 미션과 기한을 반환합니다.")
    @SecurityRequirement(name = "sessionAuth")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true, schema = @Schema(type = "string"),
            description = "같은 브라우저에서 GET /auth/csrf로 발급받은 token 값. 로그인 후 재발급하세요.")
    @ApiResponse(responseCode = "200", description = "요청 처리 완료")
    @ApiResponse(responseCode = "400", description = "요청 값 또는 사진·손 인식 데이터 오류", content = @Content)
    @ApiResponse(responseCode = "409", description = "이미 사용한 링크 또는 현재 단계에서 호출 불가", content = @Content)
    @ApiResponse(responseCode = "410", description = "인증 또는 촬영·공유 기한 만료", content = @Content)
    @ApiResponse(responseCode = "413", description = "사진 용량 제한 초과", content = @Content)
    @ApiResponse(responseCode = "503", description = "외부 분석 또는 저장 서비스 일시 장애", content = @Content)
    @PostMapping("/first-mask/confirm")
    public ResponseEntity<SecondInstruction> confirmFirst(@Parameter(description = "구매자가 전달한 sellerPath의 토큰") @PathVariable String token,
            @Valid @RequestBody ConfirmFirst input,@Parameter(hidden = true) HttpServletRequest request) {
        requireSeller(request,token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(masks.confirmFirst(token,input.boxes()));
    }

    private void requireSeller(HttpServletRequest request,String token) {
        var session=request.getSession(false);
        if (session==null || !Boolean.TRUE.equals(session.getAttribute("trueshotSeller:"+token))) {
            throw new ApiException(HttpStatus.FORBIDDEN,"인증을 시작한 브라우저에서 진행해 주세요.");
        }
    }
}
