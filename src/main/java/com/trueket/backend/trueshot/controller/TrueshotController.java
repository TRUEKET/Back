package com.trueket.backend.trueshot.controller;

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

@RestController
@RequestMapping("/trueshot")
public class TrueshotController {
    private final TrueshotRequestService service;

    public TrueshotController(TrueshotRequestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RequestDetail> create(Authentication authentication,
                                                @Valid @RequestBody CreateRequest input) {
        RequestDetail result = service.create(Long.valueOf(authentication.getName()), input.title());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping
    public ResponseEntity<List<RequestSummary>> list(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(Long.valueOf(authentication.getName())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RequestDetail> detail(Authentication authentication, @PathVariable Long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.detail(Long.valueOf(authentication.getName()), id));
    }
}
