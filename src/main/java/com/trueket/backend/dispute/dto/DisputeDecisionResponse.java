package com.trueket.backend.dispute.dto;

public record DisputeDecisionResponse(
        Long disputeId,
        Long reportId,
        String decision,
        String message
) {
}