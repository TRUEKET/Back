package com.trueket.backend.trueshot.service;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.global.storage.StorageArea;
import com.trueket.backend.global.storage.StorageTarget;
import com.trueket.backend.global.storage.SupabaseStorageDeletion;
import com.trueket.backend.trueshot.dto.TrueshotDtos.RequestDetail;
import com.trueket.backend.trueshot.dto.TrueshotDtos.RequestSummary;
import com.trueket.backend.trueshot.dto.TrueshotDtos.SellerLink;
import com.trueket.backend.trueshot.dto.TrueshotDtos.SellerStart;
import com.trueket.backend.trueshot.entity.TrueshotRequest;
import com.trueket.backend.trueshot.entity.enums.TrueshotStatus;
import com.trueket.backend.trueshot.repository.TrueshotRequestRepository;
import com.trueket.backend.trueshot.repository.TrueshotPhotoRepository;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

@Service
public class TrueshotRequestService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final TrueshotRequestRepository requests;
    private final UserRepository users;
    private final TrueshotPhotoRepository photos;
    private final SupabaseStorageDeletion storageDeletion;

    public TrueshotRequestService(TrueshotRequestRepository requests, UserRepository users,
            TrueshotPhotoRepository photos, SupabaseStorageDeletion storageDeletion) {
        this.requests = requests;
        this.users = users;
        this.photos = photos;
        this.storageDeletion = storageDeletion;
    }

    @Transactional
    public RequestDetail create(Long userId, String title) {
        User user = users.findForUpdateById(userId)
                .filter(found -> "ACTIVE".equals(found.getStatus()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String normalizedTitle = title == null ? "" : title.strip();
        if (normalizedTitle.isEmpty()) normalizedTitle = "요청 " + (requests.countByUserId(userId) + 1);
        TrueshotRequest request = TrueshotRequest.create(user, normalizedTitle, code(), token(), now);
        return RequestDetail.from(requests.save(request), now);
    }

    @Transactional(readOnly = true)
    public List<RequestSummary> list(Long userId) {
        activeUser(userId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return requests.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(request -> RequestSummary.from(request, now)).toList();
    }

    @Transactional(readOnly = true)
    public RequestDetail detail(Long userId, Long requestId) {
        activeUser(userId);
        TrueshotRequest request = requests.findByIdAndUserId(requestId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "트루샷 요청을 찾을 수 없어요."));
        return RequestDetail.from(request, OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional(readOnly = true)
    public SellerLink sellerLink(String token) {
        TrueshotRequest request = requests.findByToken(token)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "트루샷 링크를 찾을 수 없어요."));
        if (request.isExpired(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw expiredLink();
        }
        if (!request.canStart()) {
            throw usedLink();
        }
        return new SellerLink(request.getTitle(), request.getExpiresAt());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public SellerStart start(String token) {
        TrueshotRequest request = requests.findByTokenForUpdate(token)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "트루샷 링크를 찾을 수 없어요."));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (request.isExpired(now)) {
            request.failForRequestTimeout();
            request.closeShareWindow();
            throw expiredLink();
        }
        if (!request.canStart()) {
            throw usedLink();
        }
        request.start(now);
        return new SellerStart(request.getTitle(), request.getCode(),
                request.getDeadline1(), request.getExpiresAt());
    }

    @Transactional
    public void expire(Long requestId) {
        TrueshotRequest request = requests.findByIdForUpdate(requestId).orElse(null);
        if (request == null || !request.isExpired(OffsetDateTime.now(ZoneOffset.UTC))) {
            return;
        }
        request.failForRequestTimeout();
        request.closeShareWindow();
        if (request.getStatus() == TrueshotStatus.FAILED) {
            deleteOriginals(requestId);
        }
    }

    @Transactional
    public void expirePhoto(Long requestId, int number) {
        TrueshotRequest request = requests.findByIdForUpdate(requestId).orElse(null);
        if (request == null) return;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (request.isExpired(now)) {
            request.failForRequestTimeout();
        } else if (number == 1 && request.firstPhotoTimedOut(now)) {
            request.failForPhotoTimeout(1);
        } else if (number == 2 && request.secondPhotoTimedOut(now)) {
            request.failForPhotoTimeout(2);
        }
        if (request.getStatus() == TrueshotStatus.FAILED) {
            deleteOriginals(requestId);
        }
    }

    @Transactional
    public void cleanFailedOriginals(Long requestId) {
        TrueshotRequest request = requests.findByIdForUpdate(requestId).orElse(null);
        if (request != null && request.getStatus() == TrueshotStatus.FAILED) {
            deleteOriginals(requestId);
        }
    }

    private void deleteOriginals(Long requestId) {
        var stored = photos.findByRequestId(requestId).stream()
                .filter(photo -> photo.getOriginalStorageKey() != null).toList();
        if (stored.isEmpty()) return;
        storageDeletion.deleteAll(stored.stream()
                .map(photo -> new StorageTarget(StorageArea.TRUESHOT_ORIGINAL, photo.getOriginalStorageKey()))
                .toList());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        stored.forEach(photo -> photo.markOriginalDeleted(now));
    }

    private User activeUser(Long userId) {
        return users.findById(userId).filter(user -> "ACTIVE".equals(user.getStatus()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
    }

    private String code() {
        StringBuilder code = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            code.append(CODE_CHARACTERS.charAt(RANDOM.nextInt(CODE_CHARACTERS.length())));
        }
        return code.toString();
    }

    private String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ApiException expiredLink() {
        return new ApiException(HttpStatus.GONE, "이 링크는 시간이 지났어요. 구매자에게 새 링크를 요청해 주세요");
    }

    private ApiException usedLink() {
        return new ApiException(HttpStatus.CONFLICT,
                "이미 진행된 인증이에요. 재진행하려면 구매자에게 새 링크를 요청해 주세요.");
    }
}
