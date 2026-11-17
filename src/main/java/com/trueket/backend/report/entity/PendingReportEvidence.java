
package com.trueket.backend.report.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "pending_report_evidence",
        indexes = {
                @Index(
                        name = "idx_pending_evidence_expires",
                        columnList = "expires_at"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PendingReportEvidence {

    @Id
    @Column(name = "token", nullable = false, updatable = false)
    private UUID token;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "analysis_id", nullable = false, updatable = false)
    private UUID analysisId;

    @Column(name = "account_hash", nullable = false, updatable = false)
    private String accountHash;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static PendingReportEvidence create(
            Long userId,
            UUID analysisId,
            String accountHash,
            String storageKey
    ) {
        PendingReportEvidence evidence =
                new PendingReportEvidence();

        evidence.token = UUID.randomUUID();
        evidence.userId = userId;
        evidence.analysisId = analysisId;
        evidence.accountHash = accountHash;
        evidence.storageKey = storageKey;
        evidence.createdAt = OffsetDateTime.now();
        evidence.expiresAt =
                evidence.createdAt.plusMinutes(30);

        return evidence;
    }

    public boolean isExpired(OffsetDateTime now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public void consume(OffsetDateTime now) {
        if (isConsumed() || isExpired(now)) {
            throw new IllegalStateException(
                    "사용할 수 없는 임시 증빙입니다."
            );
        }

        consumedAt = now;
    }
}
