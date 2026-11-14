package com.trueket.backend.feedback.controller;

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

@RestController
@RequestMapping("/analyze/{id}/feedback")
public class FeedbackController {
    private final FeedbackService feedback;

    public FeedbackController(FeedbackService feedback) { this.feedback = feedback; }

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
