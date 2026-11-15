package com.trueket.backend.analyze.controller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.trueket.backend.analyze.dto.AnalyzeResponse;
import com.trueket.backend.analyze.service.AnalyzeService;
import com.trueket.backend.analyze.service.AnalysisSessionStore;
import com.trueket.backend.global.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Tag(name="대화 판독")
@ApiResponses({
    @ApiResponse(responseCode="400", description="입력 형식·검증 조건을 확인해 주세요.", content=@io.swagger.v3.oas.annotations.media.Content),
    @ApiResponse(responseCode="403", description="CSRF 토큰 누락 또는 현재 세션과 불일치", content=@io.swagger.v3.oas.annotations.media.Content),
    @ApiResponse(responseCode="500", description="서버 내부 오류. 응답과 서버 로그를 확인하세요.", content=@io.swagger.v3.oas.annotations.media.Content)
})
@RestController
@RequestMapping("/analyze")
public class AnalyzeController {
    private final AnalyzeService analyze;
    private final AnalysisSessionStore sessions;

    public AnalyzeController(AnalyzeService analyze, AnalysisSessionStore sessions) {
        this.analyze = analyze;
        this.sessions = sessions;
    }

    @Operation(summary="대화 캡처 판독", description="익명 사용 가능하지만 세션 쿠키와 CSRF가 필요합니다. PNG·JPG 캡처를 권장합니다. 이미지 1~10장, 장당 10MiB, 추가 정보 500자 이하. AI는 근거를 추출하고 서버가 위험도를 계산합니다. 반환 ACCOUNT는 마스킹 표시이며 신고 증빙에는 accountIndex로 선택합니다. readable=false면 analyzeId가 없고 재촬영이 필요합니다. 결과는 서버 메모리에 15분·세션별 최신 1건 보관하며 용량 제한/재시작 시 조기 소멸할 수 있습니다.", parameters=@Parameter(name="X-CSRF-TOKEN", in=ParameterIn.HEADER, required=true, schema=@io.swagger.v3.oas.annotations.media.Schema(type="string"), description="같은 브라우저에서 GET /auth/csrf로 받은 token. 가입·로그인 후 다시 발급하세요."))
    @ApiResponses({@ApiResponse(responseCode="200", description="판독 결과 또는 읽기 실패 안내"),@ApiResponse(responseCode="413", description="업로드 크기 초과", content=@io.swagger.v3.oas.annotations.media.Content),@ApiResponse(responseCode="429", description="AI 할당량 제한", content=@io.swagger.v3.oas.annotations.media.Content),@ApiResponse(responseCode="502", description="AI 응답 오류", content=@io.swagger.v3.oas.annotations.media.Content),@ApiResponse(responseCode="503", description="AI 설정·인증 오류", content=@io.swagger.v3.oas.annotations.media.Content),@ApiResponse(responseCode="504", description="AI 시간 초과", content=@io.swagger.v3.oas.annotations.media.Content)})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,
        content=@io.swagger.v3.oas.annotations.media.Content(mediaType=MediaType.MULTIPART_FORM_DATA_VALUE,
            schema=@Schema(implementation=com.trueket.backend.analyze.dto.AnalyzeUpload.class)))
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AnalyzeResponse> analyze(@Parameter(hidden=true)
            @RequestParam(value = "captures", required = false) List<MultipartFile> captures,
            @Parameter(hidden=true)
            @RequestParam(value = "additionalInfo", defaultValue = "") String additionalInfo, HttpServletRequest request) {
        var session = request.getSession();
        UUID owner = (UUID) session.getAttribute("analysisOwner");
        if (owner == null) {
            owner = UUID.randomUUID();
            session.setAttribute("analysisOwner", owner);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(analyze.analyze(captures, additionalInfo, owner));
    }

    @Operation(summary="임시 판독 결과 삭제", description="현재 브라우저가 생성한 판독의 원문·이미지·조건을 메모리에서 제거합니다. 익명도 동일 세션에서 삭제 가능합니다. 신고 DB 삭제와 별개입니다.", parameters=@Parameter(name="X-CSRF-TOKEN", in=ParameterIn.HEADER, required=true, schema=@io.swagger.v3.oas.annotations.media.Schema(type="string"), description="같은 브라우저에서 GET /auth/csrf로 받은 token. 가입·로그인 후 다시 발급하세요."))
    @ApiResponses({@ApiResponse(responseCode="204", description="삭제 완료, 본문 없음"),@ApiResponse(responseCode="404", description="다른 세션·만료·삭제된 결과", content=@io.swagger.v3.oas.annotations.media.Content)})
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, HttpServletRequest request) {
        var session = request.getSession(false);
        UUID owner = session == null ? null : (UUID) session.getAttribute("analysisOwner");
        if (owner == null) throw new ApiException(HttpStatus.NOT_FOUND, "판독 세션이 끝났어요. 다시 판독해 주세요");
        sessions.delete(id, owner);
    }
}
