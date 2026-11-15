package com.trueket.backend.dispute.dto;

public record DisputeAvailabilityResponse(
        String status,
        boolean canApply,
        int targetReportCount
) {
}