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
import java.util.*;

@Service
public class TrueshotMaskService {
    private final TrueshotRequestRepository requests;
    private final TrueshotPhotoRepository photos;
    private final TrueshotMaskDetector detector;
    private final TrueshotMaskStorage storage;
    private final TrueshotMaskRenderer renderer;
    private final ObjectMapper json=new ObjectMapper();
    private final SecondMissionService missions;
    public TrueshotMaskService(TrueshotRequestRepository requests,TrueshotPhotoRepository photos,
            TrueshotMaskDetector detector,TrueshotMaskStorage storage,TrueshotMaskRenderer renderer,
            SecondMissionService missions) {
        this.requests=requests; this.photos=photos; this.detector=detector; this.storage=storage; this.renderer=renderer;
        this.missions=missions;
    }

    @Transactional
    public MaskResult prepare(String token) {
        TrueshotRequest request=locked(token);
        TrueshotPhoto photo=first(request);
        preparePhoto(photo, () -> requireMasking(request));
        return result(token,photo);
    }

    // 최종 판정 트랜잭션에서 호출하며 가림이 완료되기 전에는 원본을 삭제하지 않는다.
    public void prepareSecond(TrueshotRequest request) {
        if (request.getPhase()!=TrueshotPhase.JUDGING) {
            throw new ApiException(HttpStatus.CONFLICT,"최종 판정 단계가 아니에요.");
        }
        var photo=photos.findByRequestIdAndNumber(request.getId(),(short)2).orElseThrow(this::notFound);
        preparePhoto(photo, () -> {
            if (request.isExpired(OffsetDateTime.now(ZoneOffset.UTC))) {
                throw new ApiException(HttpStatus.GONE,"요청 시간이 지났어요.");
            }
        });
    }

    private void preparePhoto(TrueshotPhoto photo,Runnable validate) {
        if (photo.getMaskStatus()==MaskStatus.READY || photo.getMaskStatus()==MaskStatus.FAILED) return;
        byte[] original=storage.original(photo.getOriginalStorageKey());
        var detection=detector.detect(original);
        validate.run();
        if (!detection.complete() || detection.boxes()==null || detection.boxes().isEmpty()
                || detection.boxes().stream().anyMatch(box -> box==null || !box.valid())) {
            photo.maskingFailed();
            return;
        }
        byte[] masked=renderer.render(original,detection.boxes());
        String key="trueshots/"+photo.getRequest().getId()+"/"+UUID.randomUUID()+".jpg";
        storage.saveMasked(key,masked);
        var boxes=json.createObjectNode();
        boxes.set("automatic",json.valueToTree(detection.boxes()));
        boxes.set("seller",json.createArrayNode());
        photo.maskingReady(key,boxes);
    }

    @Transactional(readOnly=true)
    public byte[] preview(String token,short number) {
        TrueshotRequest request=requests.findByToken(token).orElseThrow(this::notFound);
        if (number!=1 && number!=2) throw notFound();
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        if (request.isExpired(now)) throw new ApiException(HttpStatus.GONE,"사진 확인 시간이 지났어요.");
        if (!request.canPreviewSellerPhoto(number,now)) {
            throw new ApiException(HttpStatus.CONFLICT,"가린 사진을 확인할 수 없는 단계예요.");
        }
        var photo=photos.findByRequestIdAndNumber(request.getId(),number).orElseThrow(this::notFound);
        if (photo.getMaskStatus()!=MaskStatus.READY || photo.getMaskedImage()==null) throw notFound();
        return storage.masked(photo.getMaskedImage());
    }

    @Transactional(noRollbackFor=ApiException.class)
    public void share(String token,SharePhotos input) {
        var request=requests.findByTokenForUpdate(token).orElseThrow(this::notFound);
        // 완료한 공유 요청의 재전송은 파일을 수정하거나 공유 시각을 갱신하지 않는다.
        if (request.getStatus()==TrueshotStatus.PASSED && request.getPhase()==TrueshotPhase.COMPLETED) return;
        if (request.isExpired(OffsetDateTime.now(ZoneOffset.UTC))) {
            request.closeShareWindow();
            throw new ApiException(HttpStatus.GONE,"사진 공유 시간이 지났어요.");
        }
        requireSharing(request);
        if (!input.sharePhotos()) {
            if (!input.boxes().isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"공유하지 않을 때는 가림 영역을 비워 주세요.");
            request.finishSharing(false,OffsetDateTime.now(ZoneOffset.UTC));
            return;
        }
        var first=first(request);
        var second=photos.findByRequestIdAndNumber(request.getId(),(short)2).orElseThrow(this::notFound);
        if (first.getMaskStatus()!=MaskStatus.READY || second.getMaskStatus()!=MaskStatus.READY
                || first.getMaskedImage()==null || second.getMaskedImage()==null) {
            throw new ApiException(HttpStatus.CONFLICT,"두 사진의 개인정보 가림을 완료하지 못했어요.");
        }
        if (input.boxes().stream().anyMatch(box -> !box.valid())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"추가 가림 영역을 확인해 주세요.");
        }
        if (!input.boxes().isEmpty()) {
            byte[] masked=renderer.render(storage.masked(second.getMaskedImage()),input.boxes());
            storage.saveMasked(second.getMaskedImage(),masked);
            var boxes=second.getMaskBoxes().deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)boxes).set("seller",json.valueToTree(input.boxes()));
            second.maskingReady(second.getMaskedImage(),boxes);
        }
        if (request.isExpired(OffsetDateTime.now(ZoneOffset.UTC))) {
            request.closeShareWindow();
            throw new ApiException(HttpStatus.GONE,"사진 공유 시간이 지났어요.");
        }
        request.finishSharing(true,OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void requireSharing(TrueshotRequest request) {
        if (request.isExpired(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new ApiException(HttpStatus.GONE,"사진 공유 시간이 지났어요.");
        }
        if (request.getStatus()!=TrueshotStatus.PASSED || request.getPhase()!=TrueshotPhase.SHARE_WAITING) {
            throw new ApiException(HttpStatus.CONFLICT,"사진 공유 단계가 아니에요.");
        }
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
        var mission=missions.generate();
        request.revealSecondInstruction(mission,now);
        return new SecondInstruction(request.getInstruction2(),mission,request.getDeadline2(),request.getExpiresAt());
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
