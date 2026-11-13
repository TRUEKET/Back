package com.trueket.backend.trueshot.controller;

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

@RestController
@RequestMapping("/s/{token}")
public class TrueshotSellerController {
    private final TrueshotRequestService service;
    private final TrueshotPhotoService photos;

    public TrueshotSellerController(TrueshotRequestService service, TrueshotPhotoService photos) {
        this.service = service;
        this.photos = photos;
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

    @PostMapping(value = "/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TrueshotPhotoResponse> photo(@PathVariable String token,
            @RequestParam("n") short number, @RequestPart("photo") MultipartFile photo) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(photos.upload(token, number, photo));
    }
}
