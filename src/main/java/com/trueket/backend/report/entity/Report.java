package com.trueket.backend.report.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.trueket.backend.dispute.entity.DisputeItem;
import com.trueket.backend.report.entity.enums.ReportKind;
import com.trueket.backend.report.entity.enums.ReportStatus;
import com.trueket.backend.report.entity.enums.TradeType;
import com.trueket.backend.user.entity.User;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "reports")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "reporter_hash", nullable = false)
    private String reporterHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trade_type", nullable = false)
    private TradeType tradeType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_quotes", columnDefinition = "jsonb", nullable = false)
    private JsonNode evidenceQuotes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reference_links", columnDefinition = "jsonb", nullable = false)
    private JsonNode referenceLinks;

    @Column(name = "sent_date")
    private LocalDate sentDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "report", fetch = FetchType.LAZY)
    private List<ReportIdentifier> reportIdentifiers = new ArrayList<>();

    @OneToMany(mappedBy = "report", fetch = FetchType.LAZY)
    private List<Evidence> evidences = new ArrayList<>();

    @OneToMany(mappedBy = "report", fetch = FetchType.LAZY)
    private List<DisputeItem> disputeItems = new ArrayList<>();

    // 신고 생성
    public static Report create(
            User user,
            String reporterHash,
            ReportKind kind,
            TradeType tradeType,
            JsonNode evidenceQuotes,
            JsonNode referenceLinks,
            LocalDate sentDate
    ) {
        Report report = new Report();

        report.user = user;
        report.reporterHash = reporterHash;
        report.kind = kind;
        report.status = ReportStatus.PUBLISHED;
        report.tradeType = tradeType;
        report.evidenceQuotes = evidenceQuotes;
        report.referenceLinks = referenceLinks;
        report.sentDate = sentDate;
        report.createdAt = OffsetDateTime.now();

        return report;
    }

    // 신고 상태 변경
    public void changeStatus(ReportStatus status) {
        this.status = status;
    }

    // 신고 비공개 처리
    public void makePrivate() {
        this.status = ReportStatus.PRIVATE;
    }

    // 신고 공개 처리
    public void publish() {
        this.status = ReportStatus.PUBLISHED;
    }
    
}