package com.trueket.backend.trueshot.service;

import com.trueket.backend.trueshot.dto.HandAnalysisDtos.*;
import com.trueket.backend.trueshot.dto.SecondMission;
import java.util.ArrayList;
import java.util.List;

public final class HandMissionEvaluator {
    public static final double MIN_GESTURE_CONFIDENCE = 0.7;
    public static final double MAX_SCREEN_DISTANCE_RATIO = 0.1;

    private HandMissionEvaluator() {}

    public static Output evaluate(Input input, Status status, Screen screen, SecondMission mission) {
        List<HandResult> hands = new ArrayList<>();
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < input.hands().size(); i++) {
            Hand hand = input.hands().get(i);
            Position horizontal = Position.UNKNOWN;
            VerticalPosition vertical = VerticalPosition.UNKNOWN;
            Boolean near = null;
            Boolean matched = null;
            Double distance = null;
            Double allowedDistance = null;
            Boolean gestureMatched = mission!=null && mission.type()!=SecondMission.Type.POINT_SCREEN
                    ? "Victory".equals(hand.gesture()) && hand.confidence()>=MIN_GESTURE_CONFIDENCE : null;
            Boolean positionMatched = null;
            if (screen != null) {
                horizontal = hand.x() < screen.x() ? Position.LEFT
                        : hand.x() > screen.x() + screen.width() ? Position.RIGHT : Position.OVERLAP;
                vertical = hand.y() < screen.y() ? VerticalPosition.ABOVE
                        : hand.y() > screen.y() + screen.height() ? VerticalPosition.BELOW : VerticalPosition.OVERLAP;
                double dx = Math.max(Math.max(screen.x() - hand.x(), hand.x() - screen.x() - screen.width()), 0);
                double dy = Math.max(Math.max(screen.y() - hand.y(), hand.y() - screen.y() - screen.height()), 0);
                distance = Math.hypot(dx, dy);
                allowedDistance = Math.min(screen.width(), screen.height()) * MAX_SCREEN_DISTANCE_RATIO;
                near = distance <= allowedDistance;
                if (mission != null) {
                    if (mission.type()==SecondMission.Type.GESTURE_POSITION) {
                        positionMatched = switch (mission.position()) {
                            case LEFT -> horizontal == Position.LEFT;
                            case RIGHT -> horizontal == Position.RIGHT;
                            case ABOVE -> vertical == VerticalPosition.ABOVE;
                            case BELOW -> vertical == VerticalPosition.BELOW;
                        };
                    }
                    matched = switch (mission.type()) {
                        case GESTURE -> gestureMatched;
                        // Pointing_Up은 검지를 위로 세운 손 모양이므로 모든 방향의 가리키기를 뜻하지 않는다.
                        // 이 미션은 API 계약에서 합의한 검지 끝과 화면 사이의 거리로 비교한다.
                        case POINT_SCREEN -> near;
                        case GESTURE_POSITION -> gestureMatched && positionMatched;
                    };
                    if (matched) matches.add(i);
                }
            }
            hands.add(new HandResult(hand.x(), hand.y(), hand.gesture(), hand.confidence(),
                    horizontal, vertical, near, matched,distance,allowedDistance,gestureMatched,positionMatched));
        }
        CheckStatus check = mission == null ? CheckStatus.NOT_REQUESTED
                : status == Status.NO_HANDS ? CheckStatus.NOT_MATCHED
                : screen == null ? CheckStatus.UNDETERMINED
                : matches.isEmpty() ? CheckStatus.NOT_MATCHED : CheckStatus.MATCHED;
        return new Output(status, input.imageWidth(), input.imageHeight(), screen, List.copyOf(hands),
                new MissionCheck(mission, check, List.copyOf(matches)),null);
    }
}
