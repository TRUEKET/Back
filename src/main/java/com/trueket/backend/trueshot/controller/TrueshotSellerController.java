package com.trueket.backend.trueshot.controller;

import com.trueket.backend.trueshot.dto.TrueshotDtos.SellerLink;
import com.trueket.backend.trueshot.dto.TrueshotDtos.SellerStart;
import com.trueket.backend.trueshot.dto.TrueshotDtos.SellerStartRequest;
import com.trueket.backend.trueshot.service.TrueshotRequestService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/s/{token}")
public class TrueshotSellerController {
    private final TrueshotRequestService service;

    public TrueshotSellerController(TrueshotRequestService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<SellerLink> link(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.sellerLink(token));
    }

    @PostMapping("/start")
    public ResponseEntity<SellerStart> start(@PathVariable String token,
                                             @Valid @RequestBody SellerStartRequest input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.start(token));
    }
}
