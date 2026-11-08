package com.trueket.backend.report.entity;

import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Embeddable
@Getter
@NoArgsConstructor
@EqualsAndHashCode
public class ReportIdentifierId implements Serializable {

    private Long reportId;

    private Long identifierId;

    public ReportIdentifierId(Long reportId, Long identifierId) {
        this.reportId = reportId;
        this.identifierId = identifierId;
    }
}