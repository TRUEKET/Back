package com.trueket.backend.report.entity;

import com.trueket.backend.identifier.entity.Identifier;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "report_identifiers")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ReportIdentifier {

    @EmbeddedId
    private ReportIdentifierId id;

    @MapsId("reportId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private Report report;

    @MapsId("identifierId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "identifier_id", nullable = false)
    private Identifier identifier;

    @Column(nullable = false)
    private boolean hidden;

    // 신고와 식별 정보 연결
    public static ReportIdentifier create(
            Report report,
            Identifier identifier
    ) {
        ReportIdentifier reportIdentifier = new ReportIdentifier();

        reportIdentifier.id = new ReportIdentifierId(
                report.getId(),
                identifier.getId()
        );

        reportIdentifier.report = report;
        reportIdentifier.identifier = identifier;
        reportIdentifier.hidden = false;

        return reportIdentifier;
    }

    // 특정 식별 정보 숨김
    public void hide() {
        this.hidden = true;
    }

    // 숨김 해제
    public void show() {
        this.hidden = false;
    }
    
}