
package com.trueket.backend.report.dto;

import com.trueket.backend.report.entity.enums.ReportKind;
import com.trueket.backend.report.entity.enums.ReportStatus;

import java.util.List;

public record ReportCreateResponse(
        Long reportId,
        ReportKind kind,
        ReportStatus status,
        List<Long> identifierIds
) {
}
