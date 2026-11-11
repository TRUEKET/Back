package com.trueket.backend.analyze.controller;

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

@RestController
@RequestMapping("/analyze")
public class AnalyzeController {
    private final AnalyzeService analyze;
    private final AnalysisSessionStore sessions;

    public AnalyzeController(AnalyzeService analyze, AnalysisSessionStore sessions) {
        this.analyze = analyze;
        this.sessions = sessions;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AnalyzeResponse> analyze(@RequestParam(value = "captures", required = false) List<MultipartFile> captures,
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

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, HttpServletRequest request) {
        var session = request.getSession(false);
        UUID owner = session == null ? null : (UUID) session.getAttribute("analysisOwner");
        if (owner == null) throw new ApiException(HttpStatus.NOT_FOUND, "판독 세션이 끝났어요. 다시 판독해 주세요");
        sessions.delete(id, owner);
    }
}
