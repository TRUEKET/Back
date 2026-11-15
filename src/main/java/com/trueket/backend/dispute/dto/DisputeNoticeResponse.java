package com.trueket.backend.dispute.dto;

import com.trueket.backend.dispute.entity.DisputeItem;
import com.trueket.backend.dispute.entity.enums.DisputeItemStatus;
import com.trueket.backend.dispute.entity.enums.DisputeReason;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;


@Schema(description = "신고 게시자에게 전달된 이의제기 안내")
public record DisputeNoticeResponse(

        @Schema(description = "이의제기 ID", example = "19")
        Long disputeId,

        @Schema(description = "이의제기 사유", example = "REFUND")
        DisputeReason reason,

        @Schema(description = "신고별 이의제기 처리 상태", example = "RESPONDING")
        DisputeItemStatus status,

        @Schema(
                description = "게시자의 응답 기한",
                example = "2026-11-18T10:13:59Z"
        )
        OffsetDateTime deadline,

        @Schema(description = "현재 신고 유지·삭제 응답 가능 여부", example = "true")
        boolean canRespond,

        @Schema(description = "현재 재게시 가능 여부", example = "false")
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