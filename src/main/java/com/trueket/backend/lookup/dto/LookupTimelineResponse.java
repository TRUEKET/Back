
package com.trueket.backend.lookup.dto;

import com.trueket.backend.report.entity.enums.ReportKind;

import java.time.LocalDate;

public record LookupTimelineResponse(
        LocalDate date,
        ReportKind kind
) {
}
