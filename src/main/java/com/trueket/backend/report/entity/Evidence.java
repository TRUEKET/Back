package com.trueket.backend.report.entity;

import com.trueket.backend.report.entity.enums.EvidenceKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "evidence")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Evidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private Report report;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EvidenceKind kind;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "proof_hash")
    private String proofHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static Evidence create(
            Report report,
            EvidenceKind kind,
            String storageKey,
            String proofHash,
            OffsetDateTime expiresAt
    ) {
        Evidence evidence = new Evidence();

        evidence.report = report;
        evidence.kind = kind;
        evidence.storageKey = storageKey;
        evidence.proofHash = proofHash;
        evidence.expiresAt = expiresAt;
        evidence.createdAt = OffsetDateTime.now();

        return evidence;
    }
    
}