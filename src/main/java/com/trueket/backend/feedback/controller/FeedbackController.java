package com.trueket.backend.feedback.controller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.trueket.backend.feedback.dto.FeedbackRequest;
import com.trueket.backend.feedback.service.FeedbackService;
import com.trueket.backend.global.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@Tag(name="판독 피드백")
@ApiResponses({
    @ApiResponse(responseCode="400", description="입력 형식·검증 조건을 확인해 주세요.", content=@io.swagger.v3.oas.annotations.media.Content),
    @ApiResponse(responseCode="403", description="CSRF 토큰 누락 또는 현재 세션과 불일치", content=@io.swagger.v3.oas.annotations.media.Content),
    @ApiResponse(responseCode="500", description="서버 내부 오류. 응답과 서버 로그를 확인하세요.", content=@io.swagger.v3.oas.annotations.media.Content)
})
@RestController
@RequestMapping("/analyze/{id}/feedback")
public class FeedbackController {
    private final FeedbackService feedback;

    public FeedbackController(FeedbackService feedback) { this.feedback = feedback; }

    @Operation(summary="판독 피드백 등록", description="로그인 불필요. 판독한 동일 세션과 유효한 analyzeId가 필요합니다. HELPFUL 또는 INCORRECT만 허용하고 동일 판독 중복 제출은 거부합니다. 입력 사진·원문은 피드백 DB에 저장하지 않습니다.", parameters=@Parameter(name="X-CSRF-TOKEN", in=ParameterIn.HEADER, required=true, schema=@io.swagger.v3.oas.annotations.media.Schema(type="string"), description="같은 브라우저에서 GET /auth/csrf로 받은 token. 가입·로그인 후 다시 발급하세요."))
    @ApiResponses({@ApiResponse(responseCode="201", description="접수 완료"),@ApiResponse(responseCode="404", description="타 세션·만료·없는 판독", content=@io.swagger.v3.oas.annotations.media.Content),@ApiResponse(responseCode="409", description="이미 제출한 피드백", content=@io.swagger.v3.oas.annotations.media.Content)})
    @PostMapping
    public ResponseEntity<Map<String, String>> submit(@PathVariable UUID id,
            @Valid @RequestBody FeedbackRequest input, HttpServletRequest request) {
        var session = request.getSession(false);
        var owner = session == null ? null : (UUID) session.getAttribute("analysisOwner");
        if (owner == null) throw new ApiException(HttpStatus.NOT_FOUND, "판독 세션이 끝났어요. 다시 판독해 주세요");
        feedback.submit(id, owner, input.kind());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(Map.of("message", "소중한 의견 감사합니다"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> invalidKind() {
        return ResponseEntity.badRequest().body(Map.of("message", "피드백은 HELPFUL 또는 INCORRECT로 선택해 주세요"));
    }
}
