
package com.trueket.backend.dispute.dto;

import com.trueket.backend.dispute.entity.DisputeItem;
import com.trueket.backend.dispute.entity.enums.DisputeItemStatus;
import com.trueket.backend.dispute.entity.enums.DisputeReason;
import com.trueket.backend.dispute.entity.enums.DisputeStatus;
import com.trueket.backend.dispute.service.DisputeEvidenceStorageService;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Schema(description = "신고 게시자에게 전달된 이의제기 상세 정보")
public record DisputeDetailResponse(

        @Schema(description = "이의제기 ID", example = "19")
        Long disputeId,

        @Schema(description = "이의제기 사유", example = "REFUND")
        DisputeReason reason,

        @Schema(description = "신고별 이의제기 처리 상태")
        DisputeItemStatus status,

        @Schema(description = "게시자의 응답 기한")
        OffsetDateTime deadline,

        @Schema(description = "현재 신고 유지·삭제 응답 가능 여부")
        boolean canRespond,

        @Schema(description = "현재 재게시 가능 여부")
        boolean canRepublish,

        @Schema(
                description = "판매자가 작성한 추가 설명. "
                        + "설명이 없거나 공개 대상이 아니면 null",
                example = "구매자에게 전액 환불을 완료했습니다.",
                nullable = true
        )
        String description,

        @Schema(
                description = "마스킹된 증빙 사진 목록. "
                        + "공개 대상이 아니거나 사진이 없으면 빈 배열. "
                        + "Signed URL 유효 시간은 300초"
        )
        List<DisputeProofImageResponse> proofImages

) {

    public static DisputeDetailResponse from(
            DisputeItem item,
            DisputeEvidenceStorageService storageService
    ) {

        DisputeNoticeResponse notice =
                DisputeNoticeResponse.from(item);

        var dispute = item.getDispute();

        boolean canExposeDetails =
                dispute.getStatus() == DisputeStatus.DELIVERED
                        && (dispute.getReason() == DisputeReason.NORMAL
                        || dispute.getReason() == DisputeReason.REFUND);

        List<DisputeProofImageResponse> images =
                canExposeDetails
                        ? buildProofImages(item, storageService)
                        : List.of();

        return new DisputeDetailResponse(
                notice.disputeId(),
                notice.reason(),
                notice.status(),
                notice.deadline(),
                notice.canRespond(),
                notice.canRepublish(),
                canExposeDetails
                        ? maskDescription(dispute.getDescription())
                        : null,
                images
        );
    }

    // 증빙 이미지 Signed URL 발급
    private static List<DisputeProofImageResponse> buildProofImages(
            DisputeItem item,
            DisputeEvidenceStorageService storageService
    ) {
        var dispute = item.getDispute();
        var proofImages = dispute.getProofImages();

        if (proofImages == null || !proofImages.isArray()) {
            return List.of();
        }

        // 기존 이미지 저장 순서 및 개수 확인
        int expectedCount =
                dispute.getReason() == DisputeReason.NORMAL
                        ? 2 : 1;

        if (proofImages.size() != expectedCount) {
            return List.of();
        }

        // 예상하지 못한 경로 데이터는 반환하지 않음
        for (var image : proofImages) {
            if (!image.isTextual() || image.asText().isBlank()) {
                return List.of();
            }
        }

        List<DisputeProofImageResponse> result =
                new ArrayList<>();

        for (int i = 0; i < proofImages.size(); i++) {

            String key = proofImages.get(i).asText();

            String signedUrl =
                    storageService.createSignedUrl(
                            dispute.getId(),
                            key
                    );

            String type;

            if (dispute.getReason() == DisputeReason.REFUND) {
                type = "REFUND";
            } else {
                type = i == 0 ? "DELIVERY" : "TRANSFER";
            }

            result.add(
                    new DisputeProofImageResponse(
                            type,
                            signedUrl
                    )
            );
        }

        return List.copyOf(result);
    }

    // 판매자 설명 개인정보 마스킹
    private static String maskDescription(String description) {

        if (description == null || description.isBlank()) {
            return null;
        }

        return description
                .replaceAll(
                        "(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
                        "***"
                )
                .replaceAll(
                        "@[A-Za-z0-9_]+",
                        "***"
                )
                .replaceAll(
                        "(?<!\\d)\\d(?:[ -]?\\d){6,}(?!\\d)",
                        "***"
                );
    }
}
