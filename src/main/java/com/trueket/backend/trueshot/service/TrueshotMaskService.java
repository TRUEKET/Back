package com.trueket.backend.trueshot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.trueshot.dto.TrueshotMaskDtos.*;
import com.trueket.backend.trueshot.entity.TrueshotPhoto;
import com.trueket.backend.trueshot.entity.TrueshotRequest;
import com.trueket.backend.trueshot.entity.enums.*;
import com.trueket.backend.trueshot.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.security.SecureRandom;
import java.util.*;

@Service
public class TrueshotMaskService {
    private final TrueshotRequestRepository requests;
    private final TrueshotPhotoRepository photos;
    private final TrueshotMaskDetector detector;
    private final TrueshotMaskStorage storage;
    private final TrueshotMaskRenderer renderer;
    private final ObjectMapper json=new ObjectMapper();
    private final SecureRandom random=new SecureRandom();
    public TrueshotMaskService(TrueshotRequestRepository requests,TrueshotPhotoRepository photos,
            TrueshotMaskDetector detector,TrueshotMaskStorage storage,TrueshotMaskRenderer renderer) {
        this.requests=requests; this.photos=photos; this.detector=detector; this.storage=storage; this.renderer=renderer;
    }

    @Transactional
    public MaskResult prepare(String token) {
        TrueshotRequest request=locked(token);
        TrueshotPhoto photo=first(request);
        if (photo.getMaskStatus()==MaskStatus.READY) return result(token,photo);
        if (photo.getMaskStatus()==MaskStatus.FAILED) return result(token,photo);
        byte[] original=storage.original(photo.getOriginalStorageKey());
        var detection=detector.detect(original);
        if (!detection.complete() || detection.boxes()==null || detection.boxes().isEmpty()
                || detection.boxes().stream().anyMatch(box -> box==null || !box.valid())) {
            photo.maskingFailed();
            return result(token,photo);
        }
        requireMasking(request);
        byte[] masked=renderer.render(original,detection.boxes());
        String key="trueshots/"+request.getId()+"/"+UUID.randomUUID()+".jpg";
        storage.saveMasked(key,masked);
        var boxes=json.createObjectNode();
        boxes.set("automatic",json.valueToTree(detection.boxes()));
        boxes.set("seller",json.createArrayNode());
        photo.maskingReady(key,boxes);
        return result(token,photo);
    }

    @Transactional(readOnly=true)
    public byte[] preview(String token) {
        TrueshotRequest request=requests.findByToken(token).orElseThrow(this::notFound);
        requireMasking(request);
        TrueshotPhoto photo=first(request);
        if (photo.getMaskStatus()!=MaskStatus.READY || photo.getMaskedImage()==null) throw notFound();
        return storage.masked(photo.getMaskedImage());
    }

    @Transactional
    public SecondInstruction confirmFirst(String token,List<Box> additional) {
        TrueshotRequest request=locked(token);
        TrueshotPhoto photo=first(request);
        if (additional==null || additional.size()>300 || additional.stream().anyMatch(box -> box==null || !box.valid())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"추가 가림 영역을 확인해 주세요.");
        }
        if (photo.getMaskStatus()==MaskStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT,"자동 가림을 먼저 완료해 주세요.");
        }
        if (photo.getMaskStatus()==MaskStatus.READY && !additional.isEmpty()) {
            // 원본이 아닌 자동 가림 사진 위에만 덧칠하므로 기존 가림을 지울 수 없다.
            byte[] finalImage=renderer.render(storage.masked(photo.getMaskedImage()),additional);
            storage.saveMasked(photo.getMaskedImage(),finalImage);
            var boxes=photo.getMaskBoxes().deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)boxes).set("seller",json.valueToTree(additional));
            photo.maskingReady(photo.getMaskedImage(),boxes);
        }
        // 공개 직전의 서버 시각을 기준으로 1분을 부여한다. 전체 요청 기한은 그대로다.
        OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
        requireMasking(request);
        String side=random.nextBoolean()?"왼쪽":"오른쪽";
        String action=random.nextBoolean()?"손가락 브이를 하고":"엄지를 들고";
        String instruction="예매 화면 "+side+"에 "+action+" 촬영해 주세요.";
        request.revealSecondInstruction(instruction,now);
        return new SecondInstruction(instruction,request.getDeadline2(),request.getExpiresAt());
    }

    private TrueshotRequest locked(String token) {
        TrueshotRequest request=requests.findByTokenForUpdate(token).orElseThrow(this::notFound);
        requireMasking(request); return request;
    }
    private void requireMasking(TrueshotRequest request) {
        if (request.isExpired(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new ApiException(HttpStatus.GONE,"이 링크는 시간이 지났어요. 구매자에게 새 링크를 요청해 주세요");
        }
        if (request.getPhase()!=TrueshotPhase.FIRST_MASKING) {
            throw new ApiException(HttpStatus.CONFLICT,"첫 사진 가림 단계가 아니에요.");
        }
    }
    private TrueshotPhoto first(TrueshotRequest request) {
        return photos.findByRequestIdAndNumber(request.getId(),(short)1).orElseThrow(this::notFound);
    }
    private MaskResult result(String token,TrueshotPhoto photo) {
        boolean ready=photo.getMaskStatus()==MaskStatus.READY;
        return new MaskResult(photo.getMaskStatus(),ready?"/s/"+token+"/preview/1":null,
                ready?"가린 사진을 확인하고 필요하면 더 가려 주세요.":"사진을 가리지 못해 확인 결과만 보여드려요");
    }
    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND,"가린 사진을 찾을 수 없어요."); }
}
