package com.trueket.backend.trueshot.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class HandAnalysisDtos {
    private HandAnalysisDtos() {}

    public record Input(
            @Schema(description = "실제 업로드 이미지 너비(px), 320~40000", example = "1280") @NotNull @Min(320) @Max(40000) Integer imageWidth,
            @Schema(description = "실제 업로드 이미지 높이(px), 320~40000", example = "720") @NotNull @Min(320) @Max(40000) Integer imageHeight,
            @Schema(description = "손 인식 결과(최대 10개). 감지한 손이 없으면 빈 배열") @NotNull @Size(max = 10) List<@NotNull @Valid Hand> hands
    ) {}

    public record Hand(
            @Schema(description = "업로드 이미지 기준 검지 끝 landmark 8의 x 좌표(px), 0~imageWidth", example = "640") @NotNull Double x,
            @Schema(description = "업로드 이미지 기준 검지 끝 landmark 8의 y 좌표(px), 0~imageHeight", example = "360") @NotNull Double y,
            @Schema(description = "손 동작: None, Closed_Fist, Open_Palm, Pointing_Up, Thumb_Down, Thumb_Up, Victory, ILoveYou", example = "Victory") @NotBlank @Size(max = 64) String gesture,
            @Schema(description = "손 동작 인식 신뢰도(0~1)", example = "0.95") @NotNull Double confidence
    ) {}

    public enum Status { ANALYZED, NO_HANDS, SCREEN_NOT_FOUND, SCREEN_UNCERTAIN }
    public enum Position { LEFT, RIGHT, OVERLAP, UNKNOWN }
    public enum VerticalPosition { ABOVE, BELOW, OVERLAP, UNKNOWN }
    public enum CheckStatus { MATCHED, NOT_MATCHED, UNDETERMINED, NOT_REQUESTED }
    public record Screen(double x, double y, double width, double height, String text) {}
    public record HandResult(double x, double y, String gesture, double confidence, Position position,
                             VerticalPosition verticalPosition, Boolean nearScreen, Boolean missionMatched,
                             Double distanceToScreen, Double allowedDistance,
                             Boolean gestureMatched, Boolean positionMatched) {}
    // OCR 원문을 제외한 업로드 이미지 픽셀 좌표만 판정 기록에 남긴다.
    public record ScreenBounds(double x, double y, double width, double height) {}
    public record MissionDiagnostics(int version, int imageWidth, int imageHeight, ScreenBounds screen,
                                     double minGestureConfidence, double maxScreenDistanceRatio,
                                     List<HandResult> hands) {}
    public record MissionCheck(SecondMission mission, CheckStatus status, List<Integer> matchedHandIndexes) {}
    public record Output(Status status, int imageWidth, int imageHeight, Screen screen,
                         List<HandResult> hands, MissionCheck missionCheck, Boolean clear) {
        public Output withQuality(boolean clear) {
            return new Output(status,imageWidth,imageHeight,screen,hands,missionCheck,clear);
        }
        public MissionDiagnostics diagnostics(double minGestureConfidence,double maxScreenDistanceRatio) {
            var bounds=screen==null?null:new ScreenBounds(screen.x(),screen.y(),screen.width(),screen.height());
            return new MissionDiagnostics(1,imageWidth,imageHeight,bounds,minGestureConfidence,maxScreenDistanceRatio,hands);
        }
    }
}
