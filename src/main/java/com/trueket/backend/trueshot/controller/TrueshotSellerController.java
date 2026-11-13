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
import com.trueket.backend.trueshot.service.TrueshotMaskService;
import com.trueket.backend.trueshot.dto.TrueshotMaskDtos.*;
import com.trueket.backend.global.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/s/{token}")
public class TrueshotSellerController {
    private final TrueshotRequestService service;
    private final TrueshotPhotoService photos;
    private final TrueshotMaskService masks;

    public TrueshotSellerController(TrueshotRequestService service, TrueshotPhotoService photos, TrueshotMaskService masks) {
        this.service = service;
        this.photos = photos;
        this.masks = masks;
    }

    @GetMapping
    public ResponseEntity<SellerLink> link(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.sellerLink(token));
    }

    @PostMapping("/start")
    public ResponseEntity<SellerStart> start(@PathVariable String token,
                                             @Valid @RequestBody SellerStartRequest input, HttpServletRequest request) {
        SellerStart result=service.start(token);
        request.getSession().setAttribute("trueshotSeller:"+token,Boolean.TRUE);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping(value = "/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TrueshotPhotoResponse> photo(@PathVariable String token,
            @RequestParam("n") short number, @RequestPart("photo") MultipartFile photo, HttpServletRequest request) {
        requireSeller(request,token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(photos.upload(token, number, photo));
    }

    @PostMapping("/mask")
    public ResponseEntity<MaskResult> mask(@PathVariable String token,HttpServletRequest request) {
        requireSeller(request,token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(masks.prepare(token));
    }

    @GetMapping(value="/preview/1",produces=MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> preview(@PathVariable String token,HttpServletRequest request) {
        requireSeller(request,token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(masks.preview(token));
    }

    @PostMapping("/first-mask/confirm")
    public ResponseEntity<SecondInstruction> confirmFirst(@PathVariable String token,
            @Valid @RequestBody ConfirmFirst input,HttpServletRequest request) {
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
