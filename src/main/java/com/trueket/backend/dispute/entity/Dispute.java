package com.trueket.backend.dispute.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.trueket.backend.dispute.entity.enums.DisputeReason;
import com.trueket.backend.dispute.entity.enums.ImpersonationResult;
import com.trueket.backend.identifier.entity.Identifier;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "disputes")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Dispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_no", nullable = false, unique = true)
    private String receiptNo;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "identifier_id", nullable = false, unique = true)
    private Identifier identifier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DisputeReason reason;

    private String description;

    @Column(name = "seller_email", nullable = false)
    private String sellerEmail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proof_images", columnDefinition = "jsonb", nullable = false)
    private JsonNode proofImages;

    @Enumerated(EnumType.STRING)
    @Column(name = "impersonation_result")
    private ImpersonationResult impersonationResult;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @OneToMany(mappedBy = "dispute", fetch = FetchType.LAZY)
    private List<DisputeItem> disputeItems = new ArrayList<>();

    // 이의제기 생성
    public static Dispute create(
            String receiptNo,
            Identifier identifier,
            DisputeReason reason,
            String description,
            String sellerEmail,
            JsonNode proofImages
    ) {
        Dispute dispute = new Dispute();

        dispute.receiptNo = receiptNo;
        dispute.identifier = identifier;
        dispute.reason = reason;
        dispute.description = description;
        dispute.sellerEmail = sellerEmail;
        dispute.proofImages = proofImages;
        dispute.createdAt = OffsetDateTime.now();

        return dispute;
    }

    // 명의도용 검토 결과 등록
    public void resolve(ImpersonationResult result) {
        this.impersonationResult = result;
        this.resolvedAt = OffsetDateTime.now();
    }
    
}