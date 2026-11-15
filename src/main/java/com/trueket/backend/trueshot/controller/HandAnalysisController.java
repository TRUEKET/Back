package com.trueket.backend.trueshot.controller;

import com.trueket.backend.trueshot.dto.HandAnalysisDtos.*;
import com.trueket.backend.trueshot.service.HandAnalysisService;
import com.trueket.backend.trueshot.service.SecondMissionService;
import com.trueket.backend.trueshot.dto.SecondMission;
import com.trueket.backend.global.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class HandAnalysisController {
    private final HandAnalysisService service;
    private final SecondMissionService missions;

    public HandAnalysisController(HandAnalysisService service, SecondMissionService missions) {
        this.service = service;
        this.missions = missions;
    }

    @PostMapping(value = "/hand-analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Output> analyze(@RequestPart("photo") MultipartFile photo,
                                         @Valid @RequestPart("analysis") Input analysis,
                                         @RequestParam(value = "token", required = false) String token,
                                         HttpServletRequest request) {
        SecondMission mission = null;
        if (token != null) {
            var session = request.getSession(false);
            if (session == null || !Boolean.TRUE.equals(session.getAttribute("trueshotSeller:" + token))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "인증을 시작한 브라우저에서 진행해 주세요.");
            }
            mission = missions.current(token);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.analyze(photo, analysis, mission));
    }
}
