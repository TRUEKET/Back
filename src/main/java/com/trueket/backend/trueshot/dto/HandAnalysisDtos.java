package com.trueket.backend.trueshot.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class HandAnalysisDtos {
    private HandAnalysisDtos() {}

    public record Input(@NotNull @Min(320) @Max(40000) Integer imageWidth,
                        @NotNull @Min(320) @Max(40000) Integer imageHeight,
                        @NotNull @Size(max = 10) List<@NotNull @Valid Hand> hands) {}

    public record Hand(@NotNull Double x, @NotNull Double y,
                       @NotBlank @Size(max = 64) String gesture, @NotNull Double confidence) {}

    public enum Status { ANALYZED, NO_HANDS, SCREEN_NOT_FOUND, SCREEN_UNCERTAIN }
    public enum Position { LEFT, RIGHT, OVERLAP, UNKNOWN }
    public enum VerticalPosition { ABOVE, BELOW, OVERLAP, UNKNOWN }
    public enum CheckStatus { MATCHED, NOT_MATCHED, UNDETERMINED, NOT_REQUESTED }
    public record Screen(double x, double y, double width, double height, String text) {}
    public record HandResult(double x, double y, String gesture, double confidence, Position position,
                             VerticalPosition verticalPosition, Boolean nearScreen, Boolean missionMatched) {}
    public record MissionCheck(SecondMission mission, CheckStatus status, List<Integer> matchedHandIndexes) {}
    public record Output(Status status, int imageWidth, int imageHeight, Screen screen,
                         List<HandResult> hands, MissionCheck missionCheck, Boolean clear) {
        public Output withQuality(boolean clear) {
            return new Output(status,imageWidth,imageHeight,screen,hands,missionCheck,clear);
        }
    }
}
