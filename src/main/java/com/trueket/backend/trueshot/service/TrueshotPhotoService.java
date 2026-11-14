package com.trueket.backend.trueshot.service;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.trueshot.dto.TrueshotPhotoResponse;
import com.trueket.backend.trueshot.entity.TrueshotPhoto;
import com.trueket.backend.trueshot.entity.TrueshotRequest;
import com.trueket.backend.trueshot.entity.enums.TrueshotPhase;
import com.trueket.backend.trueshot.repository.TrueshotPhotoRepository;
import com.trueket.backend.trueshot.repository.TrueshotRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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

    public TrueshotPhotoService(TrueshotRequestRepository requests, TrueshotPhotoRepository photos,
            TrueshotImageInput input, TrueshotFirstPhotoChecker firstChecker,
            TrueshotOriginalStore originals) {
        this.requests = requests;
        this.photos = photos;
        this.input = input;
        this.firstChecker = firstChecker;
        this.originals = originals;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TrueshotPhotoResponse upload(String token, short number, MultipartFile file) {
        if (number != 1 && number != 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "사진 번호는 1 또는 2여야 합니다.");
        }
        TrueshotRequest request = requests.findByTokenForUpdate(token)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "트루샷 링크를 찾을 수 없어요."));
        OffsetDateTime receivedAt = OffsetDateTime.now(ZoneOffset.UTC);
        if (request.isExpired(receivedAt)) {
            request.failForRequestTimeout();
            throw new ApiException(HttpStatus.GONE,
                    "이 링크는 시간이 지났어요. 구매자에게 새 링크를 요청해 주세요");
        }
        if (number == 1) return uploadFirst(request, receivedAt, file);
        return uploadSecond(request, receivedAt, file);
    }

    private TrueshotPhotoResponse uploadFirst(TrueshotRequest request,
            OffsetDateTime receivedAt, MultipartFile file) {
        if (request.firstPhotoTimedOut(receivedAt)) {
            request.failForPhotoTimeout(1);
            throw new ApiException(HttpStatus.GONE, "3분 안에 첫번째 사진이 오지 않았어요.");
        }
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
            throw new ApiException(HttpStatus.GONE,
                    "이 링크는 시간이 지났어요. 구매자에게 새 링크를 요청해 주세요");
        }
        if (!result.passed()) {
            request.firstPhotoRejected(receivedAt, checkedAt);
            return new TrueshotPhotoResponse(1, request.getPhase(), false,
                    request.getDeadline1(), result.retryReasons());
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
        return new TrueshotPhotoResponse(1, request.getPhase(), true, null, List.of());
    }

    private TrueshotPhotoResponse uploadSecond(TrueshotRequest request,
            OffsetDateTime receivedAt, MultipartFile file) {
        if (request.secondPhotoTimedOut(receivedAt)) {
            request.failForPhotoTimeout(2);
            throw new ApiException(HttpStatus.GONE, "1분 안에 두번째 인증 사진이 오지 않았어요.");
        }
        if (request.getPhase() != TrueshotPhase.SECOND_WAITING) {
            throw new ApiException(HttpStatus.CONFLICT, "두번째 사진을 받을 수 없는 단계예요.");
        }
        if (photos.existsByRequestIdAndNumber(request.getId(), (short) 2)) {
            throw new ApiException(HttpStatus.CONFLICT, "두번째 사진은 한 번만 보낼 수 있어요.");
        }
        TrueshotImageInput.Prepared prepared = input.prepare(file);
        String key = key(request.getId());
        originals.upload(key, prepared.bytes(), prepared.mimeType());
        TrueshotPhoto photo = TrueshotPhoto.create(request, (short) 2, receivedAt);
        photo.storeOriginal(key);
        photos.save(photo);
        request.secondPhotoReceived(receivedAt);
        return new TrueshotPhotoResponse(2, request.getPhase(), true, null, List.of());
    }

    private String key(Long requestId) {
        return "trueshots/" + requestId + "/" + UUID.randomUUID() + ".jpg";
    }
}
