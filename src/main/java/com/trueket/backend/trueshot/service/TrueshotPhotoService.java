package com.trueket.backend.trueshot.service;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.trueshot.dto.TrueshotPhotoResponse;
import com.trueket.backend.trueshot.dto.TrueshotChecks;
import com.trueket.backend.trueshot.dto.HandAnalysisDtos.Input;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.trueshot.entity.TrueshotPhoto;
import com.trueket.backend.trueshot.entity.TrueshotRequest;
import com.trueket.backend.trueshot.entity.enums.TrueshotPhase;
import com.trueket.backend.trueshot.repository.TrueshotPhotoRepository;
import com.trueket.backend.trueshot.repository.TrueshotRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class TrueshotPhotoService {
    private final TrueshotRequestRepository requests;
    private final TrueshotPhotoRepository photos;
    private final TrueshotImageInput input;
    private final TrueshotFirstPhotoChecker firstChecker;
    private final TrueshotOriginalStore originals;
    private final HandAnalysisService hands;
    private final ObjectMapper json = new ObjectMapper();
    private final java.time.Duration maxJudgmentWait;

    public TrueshotPhotoService(TrueshotRequestRepository requests, TrueshotPhotoRepository photos,
            TrueshotImageInput input, TrueshotFirstPhotoChecker firstChecker,
            TrueshotOriginalStore originals, HandAnalysisService hands,
            @Value("${trueshot.judgment.max-wait:60s}") java.time.Duration maxJudgmentWait) {
        this.requests = requests;
        this.photos = photos;
        this.input = input;
        this.firstChecker = firstChecker;
        this.originals = originals;
        this.hands = hands;
        if (maxJudgmentWait.isZero() || maxJudgmentWait.isNegative()) throw new IllegalArgumentException("판정 대기 시간은 양수여야 합니다.");
        this.maxJudgmentWait=maxJudgmentWait;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TrueshotPhotoResponse upload(String token, short number, MultipartFile file, Input analysis) {
        if (number != 1 && number != 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "사진 번호는 1 또는 2여야 합니다.");
        }
        TrueshotRequest request = requests.findByTokenForUpdate(token)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "트루샷 링크를 찾을 수 없어요."));
        OffsetDateTime receivedAt = OffsetDateTime.now(ZoneOffset.UTC);
        if (request.isExpired(receivedAt)) {
            request.failForRequestTimeout();
            TrueshotTimeouts.check(request, OffsetDateTime.now(ZoneOffset.UTC));
        }
        if (number == 1) return uploadFirst(request, receivedAt, file);
        return uploadSecond(request, receivedAt, file, analysis);
    }

    private TrueshotPhotoResponse uploadFirst(TrueshotRequest request,
            OffsetDateTime receivedAt, MultipartFile file) {
        if (request.firstPhotoTimedOut(receivedAt)) {
            request.failForPhotoTimeout(1);
            TrueshotTimeouts.check(request, receivedAt);
        }
        TrueshotTimeouts.check(request, receivedAt);
        if (request.getPhase() != TrueshotPhase.FIRST_CHECKING) {
            throw new ApiException(HttpStatus.CONFLICT, "첫번째 사진을 받을 수 없는 단계예요.");
        }
        TrueshotImageInput.Prepared prepared = input.prepare(file);
        TrueshotFirstPhotoChecker.Result result;
        try {
            result = firstChecker.check(request.getCode(), prepared);
        } catch (ApiException e) {
            request.firstPhotoRejected(receivedAt, OffsetDateTime.now(ZoneOffset.UTC));
            throw e;
        }
        OffsetDateTime checkedAt = OffsetDateTime.now(ZoneOffset.UTC);
        if (request.isExpired(checkedAt)) {
            request.failForRequestTimeout();
            TrueshotTimeouts.check(request, OffsetDateTime.now(ZoneOffset.UTC));
        }
        if (!result.passed()) {
            request.firstPhotoRejected(receivedAt, checkedAt);
            var checks=TrueshotChecks.first(result.clear(),result.codeVisible(),result.codeMatches());
            return new TrueshotPhotoResponse(1, request.getPhase(), false,
                    request.getDeadline1(), result.retryReasons(),checks,checks.stream()
                            .filter(check -> check.status()==TrueshotChecks.Outcome.FAILED).map(TrueshotChecks.Check::message).toList());
        }
        String key = key(request.getId());
        try {
            originals.upload(key, prepared.bytes(), prepared.mimeType());
        } catch (ApiException e) {
            request.firstPhotoRejected(receivedAt, OffsetDateTime.now(ZoneOffset.UTC));
            throw e;
        }
        TrueshotPhoto photo = TrueshotPhoto.create(request, (short) 1, receivedAt);
        photo.storeOriginal(key);
        photos.save(photo);
        request.firstPhotoAccepted(checkedAt);
        return new TrueshotPhotoResponse(1, request.getPhase(), true, null, List.of(),
                TrueshotChecks.first(true,true,true),List.of());
    }

    private TrueshotPhotoResponse uploadSecond(TrueshotRequest request,
            OffsetDateTime receivedAt, MultipartFile file, Input analysis) {
        if (request.secondPhotoTimedOut(receivedAt)) {
            request.failForPhotoTimeout(2);
            TrueshotTimeouts.check(request, receivedAt);
        }
        TrueshotTimeouts.check(request, receivedAt);
        if (request.getPhase() != TrueshotPhase.SECOND_WAITING) {
            throw new ApiException(HttpStatus.CONFLICT, "두번째 사진을 받을 수 없는 단계예요.");
        }
        if (photos.existsByRequestIdAndNumber(request.getId(), (short) 2)) {
            throw new ApiException(HttpStatus.CONFLICT, "두번째 사진은 한 번만 보낼 수 있어요.");
        }
        if (analysis == null) throw new ApiException(HttpStatus.BAD_REQUEST, "두 번째 사진의 손 인식 결과를 함께 보내 주세요.");
        if (request.getMission2() == null) throw new ApiException(HttpStatus.CONFLICT, "새 트루샷 요청으로 진행해 주세요.");
        TrueshotImageInput.Prepared prepared = hands.prepare(file, analysis);
        String key = key(request.getId());
        originals.upload(key, prepared.bytes(), prepared.mimeType());
        TrueshotPhoto photo = TrueshotPhoto.create(request, (short) 2, receivedAt);
        photo.storeOriginal(key);
        photo.storeHandAnalysis(json.valueToTree(analysis));
        photos.save(photo);
        request.secondPhotoReceived(receivedAt,maxJudgmentWait);
        return new TrueshotPhotoResponse(2, request.getPhase(), true, request.getJudgmentDeadline(), List.of(),TrueshotChecks.pendingSecond(),List.of());
    }

    private String key(Long requestId) {
        return "trueshots/" + requestId + "/" + UUID.randomUUID() + ".jpg";
    }
}
