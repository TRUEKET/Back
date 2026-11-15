package com.trueket.backend.trueshot;

import com.trueket.backend.trueshot.dto.HandAnalysisDtos.*;
import com.trueket.backend.trueshot.dto.SecondMission;
import com.trueket.backend.trueshot.dto.SecondMission.*;
import com.trueket.backend.trueshot.service.HandMissionEvaluator;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HandMissionEvaluatorTests {
    private final Screen screen = new Screen(100,100,200,400,"");

    @Test void gestureOnlyUsesVictoryAndInclusiveConfidenceThreshold() {
        var mission = SecondMission.of(Type.GESTURE,null);
        assertEquals(CheckStatus.MATCHED, check(mission, hand(50,200,"Victory",.8)).missionCheck().status());
        assertEquals(CheckStatus.NOT_MATCHED, check(mission, hand(50,200,"Victory",.799)).missionCheck().status());
        assertEquals(CheckStatus.NOT_MATCHED, check(mission, hand(50,200,"Thumb_Up",.99)).missionCheck().status());
    }

    @Test void pointingUsesDistanceToRectangleIncludingDiagonalAndBoundary() {
        var mission = SecondMission.of(Type.POINT_SCREEN,null);
        assertEquals(CheckStatus.MATCHED, check(mission, hand(100,100,"None",0)).missionCheck().status());
        assertEquals(CheckStatus.MATCHED, check(mission, hand(80,200,"None",0)).missionCheck().status());
        assertEquals(CheckStatus.NOT_MATCHED, check(mission, hand(79.9,200,"Pointing_Up",.99)).missionCheck().status());
        assertEquals(CheckStatus.NOT_MATCHED, check(mission, hand(85,85,"Pointing_Up",.99)).missionCheck().status());
    }

    @Test void positionMissionSupportsAllFourDirectionsAndRequiresSameHand() {
        var candidates = List.of(hand(50,200,"Victory",.8), hand(350,200,"Victory",.8),
                hand(200,50,"Victory",.8), hand(200,550,"Victory",.8));
        for (int i=0; i<Direction.values().length; i++) {
            var mission = SecondMission.of(Type.GESTURE_POSITION,Direction.values()[i]);
            var output = check(mission,candidates.toArray(Hand[]::new));
            assertEquals(List.of(i),output.missionCheck().matchedHandIndexes());
            assertEquals(CheckStatus.NOT_MATCHED,check(mission,hand(200,200,"Victory",.99)).missionCheck().status());
        }
        var left = SecondMission.of(Type.GESTURE_POSITION,Direction.LEFT);
        assertEquals(CheckStatus.NOT_MATCHED, check(left, hand(200,200,"Victory",.99),
                hand(50,200,"Open_Palm",.99)).missionCheck().status());
        assertEquals(CheckStatus.NOT_MATCHED, check(left,hand(100,200,"Victory",.99)).missionCheck().status());
    }

    @Test void unknownScreenNoHandsAndNoMissionAreDistinct() {
        var input = new Input(800,800,List.of(hand(50,200,"Victory",.99)));
        var mission = SecondMission.of(Type.GESTURE,null);
        var unknown = HandMissionEvaluator.evaluate(input,Status.SCREEN_UNCERTAIN,null,mission);
        assertEquals(CheckStatus.UNDETERMINED,unknown.missionCheck().status());
        assertNull(unknown.hands().get(0).missionMatched());
        assertNull(unknown.hands().get(0).nearScreen());
        assertEquals(CheckStatus.NOT_MATCHED,HandMissionEvaluator.evaluate(new Input(800,800,List.of()),
                Status.NO_HANDS,null,mission).missionCheck().status());
        assertEquals(CheckStatus.NOT_REQUESTED,check(null,hand(50,200,"Victory",.99)).missionCheck().status());
    }

    private Hand hand(double x,double y,String gesture,double confidence) { return new Hand(x,y,gesture,confidence); }
    private Output check(SecondMission mission,Hand... hands) {
        return HandMissionEvaluator.evaluate(new Input(800,800,List.of(hands)),Status.ANALYZED,screen,mission);
    }
}
