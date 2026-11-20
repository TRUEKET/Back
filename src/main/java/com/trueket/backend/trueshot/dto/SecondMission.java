package com.trueket.backend.trueshot.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record SecondMission(
            @Schema(description = "두 번째 촬영 미션 종류. 신규 발급은 GESTURE 또는 GESTURE_POSITION. POINT_SCREEN은 기존 발급 건 호환용") Type type,
            @Schema(description = "미션 손 동작. POINT_SCREEN에서는 null", example = "Victory") String gesture,
            @Schema(description = "미션 기준 예매 화면과 손의 상대 방향") Direction position
    ) {
    public enum Type { GESTURE, POINT_SCREEN, GESTURE_POSITION }
    public enum Direction { LEFT, RIGHT, ABOVE, BELOW }

    public static SecondMission of(Type type, Direction position) {
        if (type == null || (type == Type.GESTURE_POSITION) != (position != null)) {
            throw new IllegalArgumentException("미션 종류와 위치가 일치하지 않습니다.");
        }
        return new SecondMission(type, type == Type.POINT_SCREEN ? null : "Victory", position);
    }

    public String instruction() {
        return switch (type) {
            case GESTURE -> "예매 화면 옆에서 브이를 하고 찍어 주세요.";
            case POINT_SCREEN -> "손가락으로 예매 화면을 가리켜 주세요.";
            case GESTURE_POSITION -> "브이를 예매 화면 " + switch (position) {
                case LEFT -> "왼쪽";
                case RIGHT -> "오른쪽";
                case ABOVE -> "위쪽";
                case BELOW -> "아래쪽";
            } + "에 두고 찍어 주세요.";
        };
    }
}
