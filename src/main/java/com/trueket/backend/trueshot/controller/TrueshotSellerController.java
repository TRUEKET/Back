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
import com.trueket.backend.trueshot.dto.HandAnalysisDtos.Input;
import com.trueket.backend.trueshot.dto.TrueshotDtos.SellerResult;
import com.trueket.backend.trueshot.service.TrueshotJudgmentService;

@RestController
@RequestMapping("/s/{token}")
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
            @RequestParam("n") short number, @RequestPart("photo") MultipartFile photo,
            @Valid @RequestPart(value="analysis",required=false) Input analysis, HttpServletRequest request) {
        requireSeller(request,token);
        var result=photos.upload(token, number, photo, analysis);
        if (number==2) {
            try {
                var judged=judgments.judge(token);
                result=new TrueshotPhotoResponse(2,judged.phase(),true,null,java.util.List.of(),
                        judged.checks().stream().filter(check -> check.photoNumber()==2).toList(),judged.failReasons());
            } catch (ApiException e) {
                // 접수는 이미 커밋했다. 외부 서비스 장애는 같은 사진으로 재시도한다.
                if (e.getStatus()!=HttpStatus.SERVICE_UNAVAILABLE) throw e;
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/mask")
    public ResponseEntity<MaskResult> mask(@PathVariable String token,HttpServletRequest request) {
        requireSeller(request,token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(masks.prepare(token));
    }

    @GetMapping(value="/preview/{number}",produces=MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> preview(@PathVariable String token,@PathVariable short number,HttpServletRequest request) {
        requireSeller(request,token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(masks.preview(token,number));
    }

    @GetMapping("/result")
    public ResponseEntity<SellerResult> result(@PathVariable String token,HttpServletRequest request) {
        requireSeller(request,token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(judgments.sellerResult(token));
    }

    @PostMapping("/judge")
    public ResponseEntity<SellerResult> judge(@PathVariable String token,HttpServletRequest request) {
        requireSeller(request,token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(judgments.judge(token));
    }

    @PostMapping("/share")
    public ResponseEntity<SellerResult> share(@PathVariable String token,@Valid @RequestBody SharePhotos input,
            HttpServletRequest request) {
        requireSeller(request,token);
        masks.share(token,input);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(judgments.sellerResult(token));
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
