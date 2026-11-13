
package com.trueket.backend.report.dto;

import com.trueket.backend.report.entity.enums.TradeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record ReportCreateRequest(
        @NotBlank String analysisProof,
        @NotNull TradeType tradeType,
        LocalDate sentDate,
        List<String> referenceLinks
) {
}
