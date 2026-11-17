package com.trueket.backend.trueshot.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.trueket.backend.trueshot.entity.enums.MaskStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

public final class TrueshotMaskDtos {
    private TrueshotMaskDtos() {}

    // 화면 크기와 관계없이 같은 영역을 가리도록 0~1 사이의 비율을 사용한다.
    public record Box(
            @Schema(description = "가림 영역 왼쪽 좌표 비율(0~1)", example = "0.1") @DecimalMin("0") @DecimalMax("1") double x,
            @Schema(description = "가림 영역 위쪽 좌표 비율(0~1)", example = "0.1") @DecimalMin("0") @DecimalMax("1") double y,
            @Schema(description = "가림 영역 너비 비율(0 초과~1). x+width는 1 이하", example = "0.2") @DecimalMin(value="0", inclusive=false) @DecimalMax("1") double width,
            @Schema(description = "가림 영역 높이 비율(0 초과~1). y+height는 1 이하", example = "0.2") @DecimalMin(value="0", inclusive=false) @DecimalMax("1") double height
    ) {
        public boolean valid() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(width) && Double.isFinite(height)
                    && x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= 1 && y + height <= 1;
        }
    }

    public record MaskResult(
            @Schema(description = "개인정보 마스킹 처리 상태") MaskStatus maskStatus,
            @Schema(description = "현재 단계에서 조회 가능한 마스킹 사진 경로. 조회 불가 시 null") String previewPath,
            @Schema(description = "처리 상태 안내 메시지") String message
    ) {}
    public record ConfirmFirst(
            @Schema(description = "추가 가림 영역 목록(최대 300개). 추가 가림이 없으면 빈 배열") @NotNull @Size(max=300) List<@NotNull @Valid Box> boxes
    ) {}
    public record SharePhotos(
            @Schema(description = "사진 공유 동의. false이면 boxes는 빈 배열이어야 함", example = "true") @NotNull Boolean sharePhotos,
            @Schema(description = "2차 사진의 자동 가림 위에 추가할 수동 가림 영역(0~1 비율, 최대 300개). 추가 가림이 없거나 공유를 거절하면 빈 배열",
                    example = "[{\"x\":0.6,\"y\":0.6,\"width\":0.1,\"height\":0.1}]") @NotNull @Size(max=300) List<@NotNull @Valid Box> boxes
    ) {}
    public record SecondInstruction(
            @Schema(description = "두 번째 사진 촬영 안내. 미션 발급 전 null") String instruction2,
            @Schema(description = "두 번째 사진의 촬영 미션. 발급 전 null") SecondMission mission2,
            @Schema(description = "두 번째 사진 제출 기한. 미션 발급 전 null") OffsetDateTime deadline2,
            @Schema(description = "인증 링크 만료 시각") OffsetDateTime expiresAt
    ) {}
}
