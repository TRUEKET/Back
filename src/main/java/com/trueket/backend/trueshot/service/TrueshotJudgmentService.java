package com.trueket.backend.trueshot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.trueshot.dto.HandAnalysisDtos.*;
import com.trueket.backend.trueshot.dto.TrueshotDtos.SellerResult;
import com.trueket.backend.trueshot.entity.TrueshotRequest;
import com.trueket.backend.trueshot.entity.enums.*;
import com.trueket.backend.trueshot.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
public class TrueshotJudgmentService {
    private final TrueshotRequestRepository requests;
    private final TrueshotPhotoRepository photos;
    private final HandAnalysisService hands;
    private final TrueshotMaskStorage storage;
    private final TrueshotMaskService masks;
    private final TrueshotRequestService lifecycle;
    private final ObjectMapper json = new ObjectMapper();

    public TrueshotJudgmentService(TrueshotRequestRepository requests, TrueshotPhotoRepository photos,
            HandAnalysisService hands, TrueshotMaskStorage storage, TrueshotMaskService masks,
            TrueshotRequestService lifecycle) {
        this.requests=requests; this.photos=photos; this.hands=hands; this.storage=storage;
        this.masks=masks; this.lifecycle=lifecycle;
    }

    // 외부 서비스 오류여도 접수 및 완료된 분석 결과를 보존하여 동일 사진으로 재시도한다.
    @Transactional(noRollbackFor = ApiException.class)
    public SellerResult judge(String token) {
        var request = requests.findByTokenForUpdate(token).orElseThrow(this::notFound);
        process(request);
        return result(request);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void judgeById(Long id) {
        var request = requests.findByIdForUpdate(id).orElse(null);
        if (request != null && request.getPhase() == TrueshotPhase.JUDGING) process(request);
    }

    @Transactional(readOnly = true)
    public SellerResult sellerResult(String token) {
        return result(requests.findByToken(token).orElseThrow(this::notFound));
    }

    private void process(TrueshotRequest request) {
        if (request.getStatus() == TrueshotStatus.PASSED || request.getStatus() == TrueshotStatus.FAILED) {
            lifecycle.cleanFinalOriginals(request.getId());
            return;
        }
        if (request.getPhase() != TrueshotPhase.JUDGING) {
            throw new ApiException(HttpStatus.CONFLICT, "두 번째 사진을 먼저 보내 주세요.");
        }
        if (expire(request)) return;
        var first = photos.findByRequestIdAndNumber(request.getId(),(short)1).orElseThrow(this::notFound);
        var second = photos.findByRequestIdAndNumber(request.getId(),(short)2).orElseThrow(this::notFound);
        if (request.getJudgmentResult() == null) {
            if (second.getHandAnalysis() == null || request.getMission2() == null) {
                throw new ApiException(HttpStatus.CONFLICT, "손 인식 결과가 없는 이전 요청입니다. 새 요청으로 진행해 주세요.");
            }
            Input input;
            try { input = json.treeToValue(second.getHandAnalysis(), Input.class); }
            catch (java.io.IOException e) { throw new ApiException(HttpStatus.CONFLICT, "저장된 손 인식 결과를 읽지 못했어요."); }
            byte[] original = storage.original(second.getOriginalStorageKey());
            var analysis = hands.analyze(new TrueshotImageInput.Prepared(original,"image/jpeg"), input, request.getMission2());
            if (expire(request)) return;
            var reasons = new ArrayList<String>();
            boolean firstVerified = request.getFirstVerifiedAt() != null;
            if (!firstVerified) reasons.add("첫 번째 사진의 코드·화질 확인이 완료되지 않음");
            if (Boolean.FALSE.equals(analysis.clear())) reasons.add("두 번째 사진이 흐리거나 예매 화면의 내용을 확인할 수 없음");
            if (analysis.missionCheck().status() != CheckStatus.MATCHED) {
                reasons.add(switch (analysis.status()) {
                    case NO_HANDS -> "두 번째 사진에서 손을 찾지 못함";
                    case SCREEN_NOT_FOUND -> "두 번째 사진에서 예매 화면을 찾지 못함";
                    case SCREEN_UNCERTAIN -> "두 번째 사진의 예매 화면을 확인할 수 없음";
                    case ANALYZED -> "두 번째 사진의 손 모양 또는 위치가 지시와 일치하지 않음";
                });
            }
            // 화면의 원문 텍스트와 내부 Storage 키는 판정 결과에 포함하지 않는다.
            var snapshot = json.createObjectNode();
            snapshot.put("firstPhotoVerified", firstVerified);
            snapshot.put("analysisStatus", analysis.status().name());
            snapshot.put("secondPhotoClear",analysis.clear());
            snapshot.put("passed", reasons.isEmpty());
            snapshot.set("missionCheck",json.valueToTree(analysis.missionCheck()));
            snapshot.set("failReasons",json.valueToTree(reasons));
            request.storeJudgment(snapshot);
        }
        var snapshot = request.getJudgmentResult();
        boolean passed = snapshot.path("passed").asBoolean();
        if (passed) {
            try { masks.prepareSecond(request); }
            catch (ApiException e) {
                if (e.getStatus()==HttpStatus.GONE && expire(request)) return;
                throw e;
            }
        }
        if (expire(request)) return;
        List<String> reasons = new ArrayList<>();
        snapshot.path("failReasons").forEach(reason -> reasons.add(reason.asText()));
        boolean shareable = first.getMaskStatus() == MaskStatus.READY && second.getMaskStatus() == MaskStatus.READY
                && first.getMaskedImage()!=null && second.getMaskedImage()!=null;
        request.finishJudgment(passed, shareable, reasons, now());
        lifecycle.cleanFinalOriginals(request.getId());
    }

    private boolean expire(TrueshotRequest request) {
        if (!request.isExpired(now())) return false;
        request.failForRequestTimeout();
        lifecycle.cleanFinalOriginals(request.getId());
        return true;
    }

    private SellerResult result(TrueshotRequest request) {
        var stored = photos.findByRequestId(request.getId());
        boolean ready = stored.size() == 2 && stored.stream().allMatch(photo -> photo.getMaskStatus() == MaskStatus.READY
                && photo.getMaskedImage()!=null);
        return SellerResult.from(request, now(), ready);
    }
    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND,"트루샷 요청 또는 사진을 찾을 수 없어요."); }
}
