package com.trueket.backend.dispute.entity;

import com.trueket.backend.dispute.entity.enums.DisputeItemStatus;
import com.trueket.backend.report.entity.Report;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(
        name = "dispute_items",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_dispute_items_dispute_report",
                        columnNames = {"dispute_id", "report_id"}
                )
        }
)
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class DisputeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dispute_id", nullable = false)
    private Dispute dispute;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private Report report;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DisputeItemStatus status;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(nullable = false)
    private OffsetDateTime deadline;

    @Column(nullable = false)
    private boolean republished;

    // 신고별 이의제기 항목 생성
    public static DisputeItem create(
            Dispute dispute,
            Report report
    ) {
        DisputeItem item = new DisputeItem();

        item.dispute = dispute;
        item.report = report;
        item.status = DisputeItemStatus.RESPONDING;
        item.deadline = OffsetDateTime.now().plusDays(3);
        item.republished = false;

        return item;
    }

    // 신고 작성자의 처리 결정
    public void decide(DisputeItemStatus decision) {
        if (decision != DisputeItemStatus.MAINTAINED
                && decision != DisputeItemStatus.DELETED
                && decision != DisputeItemStatus.PRIVATE) {
            throw new IllegalArgumentException("올바르지 않은 처리 결정입니다.");
        }

        if (this.status != DisputeItemStatus.RESPONDING) {
            throw new IllegalStateException("이미 처리된 이의제기입니다.");
        }

        this.status = decision;
        this.decidedAt = OffsetDateTime.now();

        if (decision == DisputeItemStatus.MAINTAINED) {
            this.report.publish();
        } else if (decision == DisputeItemStatus.PRIVATE) {
            this.report.makePrivate();
        }
    }

    // 트루켓의 명의도용 검토 진행
    public void markReviewing() {
        if (this.status != DisputeItemStatus.RESPONDING) {
            throw new IllegalStateException("검토할 수 없는 상태입니다.");
        }

        this.status = DisputeItemStatus.REVIEWING;
        this.report.changeStatus(
                com.trueket.backend.report.entity.enums.ReportStatus.REVIEWING
        );
    }

    // 비공개 신고 재공개
    public void republish() {
        if (this.status != DisputeItemStatus.PRIVATE) {
            throw new IllegalStateException("비공개 상태의 신고만 재공개할 수 있습니다.");
        }

        if (this.republished) {
            throw new IllegalStateException("이미 재공개한 신고입니다.");
        }

        this.republished = true;
        this.status = DisputeItemStatus.MAINTAINED;
        this.report.publish();
    }
    
}