package com.trueket.backend.dispute.dto;

public record DisputeCreateResponse(
        Long disputeId,
        String receiptNo,
        String status,
        int targetReportCount,
        String message
) {
}