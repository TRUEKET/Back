package com.trueket.backend.dispute.dto;

import jakarta.validation.constraints.NotNull;

public record DisputeRepublishRequest(
        @NotNull Long reportId
) {
}