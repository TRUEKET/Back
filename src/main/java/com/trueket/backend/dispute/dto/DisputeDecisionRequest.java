package com.trueket.backend.dispute.dto;

import com.trueket.backend.dispute.entity.enums.DisputeItemStatus;
import jakarta.validation.constraints.NotNull;

public record DisputeDecisionRequest(
        @NotNull Long reportId,
        @NotNull DisputeItemStatus decision
) {
}