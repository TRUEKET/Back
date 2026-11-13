package com.trueket.backend.trueshot.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.trueket.backend.trueshot.entity.TrueshotRequest;
import com.trueket.backend.trueshot.entity.enums.TrueshotPhase;
import com.trueket.backend.trueshot.entity.enums.TrueshotStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.StreamSupport;

public final class TrueshotDtos {
    private TrueshotDtos() {}

    public record CreateRequest(
            @Size(max = 20, message = "요청 제목은 20자 이하로 입력해 주세요")
            String title
    ) {}

    public record SellerStartRequest(
            @AssertTrue(message = "사진 전달에 동의해야 인증을 시작할 수 있어요")
            boolean sellerConsented
    ) {}

    public record RequestSummary(
            Long id, String title, TrueshotStatus status, TrueshotPhase phase,
            OffsetDateTime createdAt, OffsetDateTime expiresAt
    ) {
        public static RequestSummary from(TrueshotRequest request, OffsetDateTime now) {
            return new RequestSummary(request.getId(), request.getTitle(),
                    effectiveStatus(request, now), effectivePhase(request, now),
                    request.getCreatedAt(), request.getExpiresAt());
        }
    }

    public record RequestDetail(
            Long id, String title, String code, String sellerPath,
            TrueshotStatus status, TrueshotPhase phase, String instruction2,
            OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime startedAt,
            OffsetDateTime deadline1, OffsetDateTime deadline2, OffsetDateTime photosSharedAt,
            List<String> failReasons
    ) {
        public static RequestDetail from(TrueshotRequest request, OffsetDateTime now) {
            boolean timeout = request.isExpired(now) && request.getStatus() != TrueshotStatus.PASSED
                    && request.getStatus() != TrueshotStatus.FAILED;
            List<String> reasons = timeout
                    ? List.of(request.getStartedAt() == null
                        ? "30분 안에 시작하지 않음" : "30분 안에 인증이 완료되지 않음")
                    : reasonList(request.getFailReasons());
            return new RequestDetail(request.getId(), request.getTitle(), request.getCode(),
                    "/s/" + request.getToken(),
                    effectiveStatus(request, now), effectivePhase(request, now),
                    request.getInstruction2(),
                    request.getCreatedAt(), request.getExpiresAt(), request.getStartedAt(),
                    request.getDeadline1(), request.getDeadline2(), request.getPhotosSharedAt(), reasons);
        }
    }

    private static List<String> reasonList(JsonNode reasons) {
        if (reasons == null || !reasons.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(reasons.spliterator(), false).map(JsonNode::asText).toList();
    }

    public record SellerLink(String title, OffsetDateTime expiresAt) {}

    public record SellerStart(String title, String code, OffsetDateTime deadline1,
                              OffsetDateTime expiresAt) {}

    private static TrueshotStatus effectiveStatus(TrueshotRequest request, OffsetDateTime now) {
        if (request.isExpired(now) && request.getStatus() != TrueshotStatus.PASSED) {
            return TrueshotStatus.FAILED;
        }
        return request.getStatus();
    }

    private static TrueshotPhase effectivePhase(TrueshotRequest request, OffsetDateTime now) {
        if (request.isExpired(now)) {
            return request.getStatus() == TrueshotStatus.PASSED
                    ? TrueshotPhase.COMPLETED : TrueshotPhase.FAILED;
        }
        return request.getPhase();
    }
}
