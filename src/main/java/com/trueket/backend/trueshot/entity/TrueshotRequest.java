package com.trueket.backend.trueshot.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.trueket.backend.trueshot.entity.enums.TrueshotPhase;
import com.trueket.backend.trueshot.entity.enums.TrueshotStatus;
import com.trueket.backend.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "trueshot_requests")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class TrueshotRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 4)
    private String code;

    @Column(nullable = false, unique = true)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TrueshotStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TrueshotPhase phase;

    @Column(name = "instruction_2")
    private String instruction2;

    @Column(name = "deadline_1")
    private OffsetDateTime deadline1;

    @Column(name = "deadline_2")
    private OffsetDateTime deadline2;

    // 요청 유효 기간은 30분이며, 기록은 purgeAt까지 보관한다.
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "purge_at", nullable = false)
    private OffsetDateTime purgeAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fail_reasons", columnDefinition = "jsonb", nullable = false)
    private JsonNode failReasons;

    @Column(name = "seller_consented_at")
    private OffsetDateTime sellerConsentedAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "first_verified_at")
    private OffsetDateTime firstVerifiedAt;

    @Column(name = "instruction_2_revealed_at")
    private OffsetDateTime instruction2RevealedAt;

    @Column(name = "second_received_at")
    private OffsetDateTime secondReceivedAt;

    @Column(name = "judged_at")
    private OffsetDateTime judgedAt;

    // 판정은 성공했지만 판매자가 30분 안에 사진을 공유하지 않은 경우 null이다.
    @Column(name = "photos_shared_at")
    private OffsetDateTime photosSharedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static TrueshotRequest create(
            User user, String title, String code, String token, OffsetDateTime now
    ) {
        TrueshotRequest request = new TrueshotRequest();
        request.user = user;
        request.title = title;
        request.code = code;
        request.token = token;
        request.status = TrueshotStatus.PENDING;
        request.phase = TrueshotPhase.WAITING;
        request.expiresAt = now.plusMinutes(30);
        request.purgeAt = now.plusDays(7);
        request.failReasons = JsonNodeFactory.instance.arrayNode();
        request.createdAt = now;
        return request;
    }

    public boolean isExpired(OffsetDateTime now) {
        return !now.isBefore(expiresAt);
    }

    public boolean canStart() {
        return status == TrueshotStatus.PENDING && phase == TrueshotPhase.WAITING;
    }

    public void start(OffsetDateTime now) {
        if (!canStart() || isExpired(now)) {
            throw new IllegalStateException("시작할 수 없는 트루샷 요청입니다.");
        }
        status = TrueshotStatus.IN_PROGRESS;
        phase = TrueshotPhase.FIRST_CHECKING;
        sellerConsentedAt = now;
        startedAt = now;
        deadline1 = now.plusMinutes(3);
    }

    public void failForRequestTimeout() {
        if (status == TrueshotStatus.FAILED || status == TrueshotStatus.PASSED) {
            return;
        }
        status = TrueshotStatus.FAILED;
        phase = TrueshotPhase.FAILED;
        ArrayNode reasons = JsonNodeFactory.instance.arrayNode();
        reasons.add(startedAt == null ? "30분 안에 시작하지 않음" : "30분 안에 인증이 완료되지 않음");
        failReasons = reasons;
    }

    public void closeShareWindow() {
        if (status == TrueshotStatus.PASSED && phase == TrueshotPhase.SHARE_WAITING) {
            phase = TrueshotPhase.COMPLETED;
        }
    }

    public boolean firstPhotoTimedOut(OffsetDateTime now) {
        return phase == TrueshotPhase.FIRST_CHECKING && deadline1 != null && !now.isBefore(deadline1);
    }

    public boolean secondPhotoTimedOut(OffsetDateTime now) {
        return phase == TrueshotPhase.SECOND_WAITING && deadline2 != null && !now.isBefore(deadline2);
    }

    public void failForPhotoTimeout(int number) {
        if (status == TrueshotStatus.FAILED || status == TrueshotStatus.PASSED) return;
        status = TrueshotStatus.FAILED;
        phase = TrueshotPhase.FAILED;
        failReasons = JsonNodeFactory.instance.arrayNode().add(number == 1
                ? "첫 번째 사진이 시간 내에 전송되지 않음"
                : "두 번째 사진이 시간 내에 전송되지 않음");
    }

    // 첫 번째 사진 확인 중에는 3분 제한 시간이 줄어들지 않는다.
    public void firstPhotoRejected(OffsetDateTime receivedAt, OffsetDateTime checkedAt) {
        if (phase != TrueshotPhase.FIRST_CHECKING) throw new IllegalStateException("첫 사진 확인 단계가 아닙니다.");
        deadline1 = deadline1.plus(java.time.Duration.between(receivedAt, checkedAt));
    }

    public void firstPhotoAccepted(OffsetDateTime checkedAt) {
        if (phase != TrueshotPhase.FIRST_CHECKING) throw new IllegalStateException("첫 사진 확인 단계가 아닙니다.");
        firstVerifiedAt = checkedAt;
        phase = TrueshotPhase.FIRST_MASKING;
    }

    public void secondPhotoReceived(OffsetDateTime receivedAt) {
        if (phase != TrueshotPhase.SECOND_WAITING) throw new IllegalStateException("두 번째 촬영 단계가 아닙니다.");
        secondReceivedAt = receivedAt;
        phase = TrueshotPhase.JUDGING;
    }

    public void revealSecondInstruction(String instruction, OffsetDateTime now) {
        if (phase != TrueshotPhase.FIRST_MASKING || isExpired(now)) throw new IllegalStateException("두 번째 지시를 공개할 수 없습니다.");
        instruction2=instruction;
        instruction2RevealedAt=now;
        deadline2=now.plusMinutes(1);
        phase=TrueshotPhase.SECOND_WAITING;
    }
}
