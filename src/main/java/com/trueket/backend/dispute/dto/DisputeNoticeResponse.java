package com.trueket.backend.dispute.dto;

import com.trueket.backend.dispute.entity.DisputeItem;
import com.trueket.backend.dispute.entity.enums.DisputeItemStatus;
import com.trueket.backend.dispute.entity.enums.DisputeReason;

import java.time.OffsetDateTime;

public record DisputeNoticeResponse(
        Long disputeId,
        DisputeReason reason,
        DisputeItemStatus status,
        OffsetDateTime deadline,
        boolean canRespond,
        boolean canRepublish
) {
    public static DisputeNoticeResponse from(DisputeItem item) {

        boolean beforeDeadline =
                OffsetDateTime.now().isBefore(item.getDeadline());

        boolean canRespond =
                item.getStatus() == DisputeItemStatus.RESPONDING
                        && beforeDeadline;

        boolean canRepublish =
                item.getStatus() == DisputeItemStatus.PRIVATE
                        && !item.isRepublished();

        return new DisputeNoticeResponse(
                item.getDispute().getId(),
                item.getDispute().getReason(),
                item.getStatus(),
                item.getDeadline(),
                canRespond,
                canRepublish
        );
    }
}