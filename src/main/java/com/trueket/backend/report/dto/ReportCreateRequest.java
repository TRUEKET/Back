
package com.trueket.backend.report.dto;

import com.trueket.backend.report.entity.enums.ReportKind;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ReportCreateRequest(
        @NotNull UUID analysisId,
        @NotNull ReportKind kind,
        LocalDate sentDate,
        List<String> referenceLinks,
        UUID evidenceToken
) {
}
