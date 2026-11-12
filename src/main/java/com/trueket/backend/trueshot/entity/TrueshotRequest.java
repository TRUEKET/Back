package com.trueket.backend.trueshot.entity;

import com.fasterxml.jackson.databind.JsonNode;
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
}
