package com.trueket.backend.trueshot.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.trueket.backend.trueshot.entity.TrueshotRequest;
import com.trueket.backend.trueshot.entity.TrueshotPhoto;
import com.trueket.backend.trueshot.entity.enums.MaskStatus;
import com.trueket.backend.trueshot.entity.enums.TrueshotPhase;
import com.trueket.backend.trueshot.entity.enums.TrueshotStatus;
import com.trueket.backend.trueshot.entity.enums.ShareEndReason;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

public final class TrueshotDtos {
    private TrueshotDtos() {}
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    public record TrueshotError(
            @Schema(description = "시간 초과 또는 세션 오류 코드", example = "FIRST_PHOTO_TIMEOUT") String code,
            @Schema(description = "화면에 표시할 안내 문구", example = "첫 번째 사진 제출 시간이 지났어요. 구매자에게 새 링크를 요청해 주세요.") String message
    ) {}

    public record CreateRequest(
            @Schema(description = "요청 제목. 생성 시 최대 20자이며 null·공백은 기본 제목 사용", example = "공연 티켓 인증") @Size(max = 20, message = "요청 제목은 20자 이하로 입력해 주세요")
            String title
    ) {}

    public record SellerStartRequest(
            @Schema(description = "사진 전달 동의. 인증을 시작하려면 반드시 true", example = "true") @AssertTrue(message = "사진 전달에 동의해야 인증을 시작할 수 있어요")
            boolean sellerConsented
    ) {}

    public record UpdateTitleRequest(
            @Schema(description = "수정할 제목. 필수, 공백만 입력 불가, 최대 20자. 앞뒤 공백은 제거", example = "DAY6 티켓 인증")
            @NotBlank(message = "제목을 입력해 주세요")
            @Size(max = 20, message = "요청 제목은 20자 이하로 입력해 주세요") String title
    ) {}


    public record RequestSummary(
            @Schema(description = "트루샷 요청 ID", example = "1")
            Long id,

            @Schema(
                    description = "요청 제목. 생성 시 최대 20자이며 null·공백은 기본 제목 사용",
                    example = "공연 티켓 인증"
            )
            String title,

            @Schema(description = "인증 요청 상태")
            TrueshotStatus status,

            @Schema(description = "현재 인증 진행 단계")
            TrueshotPhase phase,

            @Schema(description = "요청 생성 시각")
            OffsetDateTime createdAt,

            @Schema(description = "인증 링크 만료 시각")
            OffsetDateTime expiresAt,

            // 신규 추가
            @Schema(
                    description = "인증 실패 코드. 실패하지 않은 경우 null",
                    allowableValues = {
                            "REQUEST_TIMEOUT",
                            "FIRST_PHOTO_TIMEOUT",
                            "SECOND_PHOTO_TIMEOUT",
                            "ANALYSIS_TIMEOUT",
                            "VERIFICATION_FAILED"
                    },
                    example = "REQUEST_TIMEOUT",
                    nullable = true
            )
            String failureCode,

            @Schema(description = "판매자가 인증 사진을 공유한 시각. 공유 전 또는 공유하지 않고 종료한 경우 null", nullable = true)
            OffsetDateTime photosSharedAt,

            @Schema(description = "사진 미공유 종료 사유. 공유 대기·공유 완료·인증 실패 및 원인을 기록하지 않은 과거 종료 건은 null", nullable = true)
            ShareEndReason shareEndReason
    ) {
        public static RequestSummary from(
                TrueshotRequest request,
                OffsetDateTime now
        ) {
            var failure = request.failureCode(now);

            return new RequestSummary(
                    request.getId(),
                    request.getTitle(),
                    effectiveStatus(request, now),
                    effectivePhase(request, now),
                    request.getCreatedAt(),
                    request.getExpiresAt(),
                    failure == null ? null : failure.name(),
                    request.getPhotosSharedAt(),
                    request.shareEndReason(now)
            );
        }
    }


    public record RequestDetail(
            @Schema(description = "트루샷 요청 ID", example = "1") Long id,
            @Schema(description = "요청 제목. 생성 시 최대 20자이며 null·공백은 기본 제목 사용", example = "공연 티켓 인증") String title,
            @Schema(description = "사진에 함께 표시할 인증 코드") String code,
            @Schema(description = "판매자에게 전달할 인증 링크의 상대 경로", example = "/s/{token}") String sellerPath,
            @Schema(description = "인증 요청 상태") TrueshotStatus status,
            @Schema(description = "현재 인증 진행 단계") TrueshotPhase phase,
            @Schema(description = "두 번째 사진 촬영 안내. 미션 발급 전 null") String instruction2,
            @Schema(description = "두 번째 사진의 촬영 미션. 발급 전 null") SecondMission mission2,
            @Schema(description = "요청 생성 시각") OffsetDateTime createdAt,
            @Schema(description = "인증 링크 만료 시각") OffsetDateTime expiresAt,
            @Schema(description = "판매자 인증 시작 시각. 시작 전 null") OffsetDateTime startedAt,
            @Schema(description = "첫 번째 사진 제출 기한") OffsetDateTime deadline1,
            @Schema(description = "두 번째 사진 제출 기한. 미션 발급 전 null") OffsetDateTime deadline2,
            @Schema(description = "사진 공유 완료 시각. 미공유 시 null") OffsetDateTime photosSharedAt,
            @Schema(description = "인증 실패 사유 목록") List<String> failReasons,
            @Schema(description = "최종 판정 완료 시각. 판정 전 null") OffsetDateTime judgedAt,
            @Schema(description = "최종 판정 상세. 판정 전 null") Map<String,Object> judgmentResult,
            @Schema(description = "조회 가능한 사진 정보 목록") List<PhotoLink> photos,
            @Schema(description = "실패 코드. REQUEST_TIMEOUT, FIRST_PHOTO_TIMEOUT, SECOND_PHOTO_TIMEOUT, ANALYSIS_TIMEOUT, VERIFICATION_FAILED") String failureCode,
            @Schema(description = "응답을 생성한 서버 시각") OffsetDateTime serverTime,
            @Schema(description = "사진 미공유 종료 사유. SHARE_TIMEOUT, MASK_FAILED, DECLINED. 미종료·공유 완료·과거 원인 불명은 null", nullable = true) ShareEndReason shareEndReason
    ) {
        public static RequestDetail from(TrueshotRequest request, OffsetDateTime now) {
            return from(request, now, List.of());
        }

        public static RequestDetail from(TrueshotRequest request, OffsetDateTime now, List<PhotoLink> photos) {
            List<String> reasons = failureReasons(request,now);
            return new RequestDetail(request.getId(), request.getTitle(), request.getCode(),
                    "/s/" + request.getToken(),
                    effectiveStatus(request, now), effectivePhase(request, now),
                    request.getInstruction2(), request.getMission2(),
                    request.getCreatedAt(), request.getExpiresAt(), request.getStartedAt(),
                    request.getDeadline1(), request.getDeadline2(), request.getPhotosSharedAt(), reasons,
                    request.getJudgedAt(), request.getJudgedAt()==null || request.getJudgmentResult()==null ? null : JSON.convertValue(request.getJudgmentResult(),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {}), photos,
                    request.failureCode(now)==null?null:request.failureCode(now).name(),now,request.shareEndReason(now));
        }
    }

    private static List<String> reasonList(JsonNode reasons) {
        if (reasons == null || !reasons.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(reasons.spliterator(), false).map(JsonNode::asText).toList();
    }

    public record SellerLink(
            @Schema(description = "요청 제목. 생성 시 최대 20자이며 null·공백은 기본 제목 사용", example = "공연 티켓 인증") String title,
            @Schema(description = "인증 링크 만료 시각") OffsetDateTime expiresAt
    ) {}

    public record SellerStart(
            @Schema(description = "요청 제목. 생성 시 최대 20자이며 null·공백은 기본 제목 사용", example = "공연 티켓 인증") String title,
            @Schema(description = "사진에 함께 표시할 인증 코드") String code,
            @Schema(description = "첫 번째 사진 제출 기한") OffsetDateTime deadline1,
            @Schema(description = "인증 링크 만료 시각") OffsetDateTime expiresAt
    ) {}

    public record PhotoLink(
            @Schema(description = "사진 번호: 1 또는 2", example = "1") int number,
            @Schema(description = "구매자가 조회할 공유 사진의 상대 경로", example = "/trueshot/1/photos/1") String url
    ) {}
    public record SellerPhoto(
            @Schema(description = "사진 번호: 1 또는 2", example = "1") int number,
            @Schema(description = "개인정보 마스킹 처리 상태") MaskStatus maskStatus,
            @Schema(description = "현재 단계에서 조회 가능한 마스킹 사진 경로. 조회 불가 시 null") String previewPath
    ) {}

    public record SellerResult(
            @Schema(description = "인증 요청 상태") TrueshotStatus status,
            @Schema(description = "현재 인증 진행 단계") TrueshotPhase phase,
            @Schema(description = "최종 판정 완료 시각. 판정 전 null") OffsetDateTime judgedAt,
            @Schema(description = "사진 공유 완료 시각. 미공유 시 null") OffsetDateTime photosSharedAt,
            @Schema(description = "현재 사진 공유 가능 여부") boolean canShare,
            @Schema(description = "처리 상태 안내 메시지") String message,
            @Schema(description = "사진별 품질·인증 코드·촬영 미션 검사 결과") List<TrueshotChecks.Check> checks,
            @Schema(description = "인증 실패 사유 목록") List<String> failReasons,
            @Schema(description = "최종 판정 제한 시각") OffsetDateTime judgmentDeadline,
            @Schema(description = "JUDGING 단계에서 결과 재조회 간격(ms). 그 외 null", example = "2000") Long pollAfterMillis,
            @Schema(description = "실패 코드. REQUEST_TIMEOUT, FIRST_PHOTO_TIMEOUT, SECOND_PHOTO_TIMEOUT, ANALYSIS_TIMEOUT, VERIFICATION_FAILED") String failureCode,
            @Schema(description = "요청 제목. 생성 시 최대 20자이며 null·공백은 기본 제목 사용", example = "공연 티켓 인증") String title,
            @Schema(description = "사진에 함께 표시할 인증 코드") String code,
            @Schema(description = "두 번째 사진 촬영 안내. 미션 발급 전 null") String instruction2,
            @Schema(description = "두 번째 사진의 촬영 미션. 발급 전 null") SecondMission mission2,
            @Schema(description = "인증 링크 만료 시각") OffsetDateTime expiresAt,
            @Schema(description = "첫 번째 사진 제출 기한") OffsetDateTime deadline1,
            @Schema(description = "두 번째 사진 제출 기한. 미션 발급 전 null") OffsetDateTime deadline2,
            @Schema(description = "응답을 생성한 서버 시각") OffsetDateTime serverTime,
            @Schema(description = "조회 가능한 사진 정보 목록") List<SellerPhoto> photos,
            @Schema(description = "현재 단계의 마감 시각. 촬영·판정 기한과 전체 요청 기한 중 빠른 시각이며 종료 상태는 null") OffsetDateTime activeDeadline,
            @Schema(description = "서버 시각 기준 남은 시간(ms). 새로고침으로 초기화되지 않으며 종료 상태는 null") Long remainingMillis,
            @Schema(description = "사진 미공유 종료 사유. SHARE_TIMEOUT, MASK_FAILED, DECLINED. 미종료·공유 완료·과거 원인 불명은 null", nullable = true) ShareEndReason shareEndReason
    ) {
        public static SellerResult from(TrueshotRequest request, OffsetDateTime now, boolean masksReady) {
            return build(request,now,masksReady,List.of());
        }

        public static SellerResult from(TrueshotRequest request,OffsetDateTime now,List<TrueshotPhoto> stored) {
            boolean ready=stored.size()==2 && stored.stream().allMatch(photo -> photo.getMaskStatus()==MaskStatus.READY
                    && photo.getMaskedImage()!=null);
            var photos=stored.stream().sorted(java.util.Comparator.comparing(TrueshotPhoto::getNumber))
                    .map(photo -> new SellerPhoto(photo.getNumber(),photo.getMaskStatus(),
                            photo.getMaskStatus()==MaskStatus.READY && photo.getMaskedImage()!=null
                                    && request.canPreviewSellerPhoto(photo.getNumber(),now)
                                    ?"/s/"+request.getToken()+"/preview/"+photo.getNumber():null)).toList();
            return build(request,now,ready,photos);
        }

        private static SellerResult build(TrueshotRequest request,OffsetDateTime now,boolean masksReady,List<SellerPhoto> photos) {
            var status = effectiveStatus(request, now);
            var phase = effectivePhase(request, now);
            boolean canShare = phase == TrueshotPhase.SHARE_WAITING && masksReady && !request.isExpired(now);
            var reasons=failureReasons(request,now);
            String message = status == TrueshotStatus.FAILED ? (reasons.isEmpty()?"인증하지 못했어요.":reasons.get(0))
                    : status == TrueshotStatus.PASSED ? "사진 확인을 완료했어요."
                    : phase==TrueshotPhase.JUDGING && request.getSecondReceivedAt()!=null
                            && !now.isBefore(request.getSecondReceivedAt().plusSeconds(10))
                            ? "사진 분석이 지연되고 있어요. 자동으로 다시 확인 중입니다."
                    : "사진을 확인하고 있어요.";
            boolean finished=status==TrueshotStatus.PASSED || status==TrueshotStatus.FAILED;
            var failure = request.failureCode(now);
            String failureCode=failure==null?null:failure.name();
            if (failure!=null && failure.isTimeout()) message=failure.message();
            var activeDeadline=TrueshotDtos.activeDeadline(request,now);
            Long remainingMillis=activeDeadline==null?null:Math.max(0L,java.time.Duration.between(now,activeDeadline).toMillis());
            return new SellerResult(status, phase, request.getJudgedAt(), request.getPhotosSharedAt(), canShare, message,
                    TrueshotChecks.result(request,finished),reasons,request.getJudgmentDeadline(),
                    phase==TrueshotPhase.JUDGING?2000L:null,failureCode,
                    request.getTitle(),request.getCode(),request.getInstruction2(),request.getMission2(),
                    request.getExpiresAt(),request.getDeadline1(),request.getDeadline2(),now,photos,activeDeadline,remainingMillis,request.shareEndReason(now));
        }
    }

    private static OffsetDateTime activeDeadline(TrueshotRequest request,OffsetDateTime now) {
        OffsetDateTime stageDeadline=switch(effectivePhase(request,now)) {
            case FIRST_CHECKING -> request.getDeadline1();
            case SECOND_WAITING -> request.getDeadline2();
            case JUDGING -> request.getJudgmentDeadline();
            case WAITING, FIRST_MASKING, SHARE_WAITING -> request.getExpiresAt();
            case COMPLETED, FAILED -> null;
        };
        return stageDeadline==null?null:stageDeadline.isBefore(request.getExpiresAt())?stageDeadline:request.getExpiresAt();
    }

    private static List<String> failureReasons(TrueshotRequest request,OffsetDateTime now) {
        if (request.getStatus()==TrueshotStatus.PASSED || request.getStatus()==TrueshotStatus.FAILED) {
            return reasonList(request.getFailReasons());
        }
        if (request.isExpired(now)) return List.of(request.getStartedAt()==null
                ? "30분 안에 시작하지 않음":"30분 안에 인증이 완료되지 않음");
        if (request.judgmentTimedOut(now)) return List.of(TrueshotRequest.ANALYSIS_TIMEOUT_REASON);
        if (request.firstPhotoTimedOut(now)) return List.of("첫 번째 사진이 시간 내에 전송되지 않음");
        if (request.secondPhotoTimedOut(now)) return List.of("두 번째 사진이 시간 내에 전송되지 않음");
        return List.of();
    }

    private static TrueshotStatus effectiveStatus(TrueshotRequest request, OffsetDateTime now) {
        if (request.getStatus() != TrueshotStatus.PASSED && (request.isExpired(now)
                || request.firstPhotoTimedOut(now) || request.secondPhotoTimedOut(now) || request.judgmentTimedOut(now))) {
            return TrueshotStatus.FAILED;
        }
        return request.getStatus();
    }

    private static TrueshotPhase effectivePhase(TrueshotRequest request, OffsetDateTime now) {
        if (request.isExpired(now)) {
            return request.getStatus() == TrueshotStatus.PASSED
                    ? TrueshotPhase.COMPLETED : TrueshotPhase.FAILED;
        }
        if (request.firstPhotoTimedOut(now) || request.secondPhotoTimedOut(now) || request.judgmentTimedOut(now)) return TrueshotPhase.FAILED;
        return request.getPhase();
    }
}
