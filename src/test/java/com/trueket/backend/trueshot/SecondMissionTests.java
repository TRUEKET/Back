package com.trueket.backend.trueshot;

import com.trueket.backend.trueshot.dto.SecondMission;
import com.trueket.backend.trueshot.dto.SecondMission.*;
import com.trueket.backend.trueshot.entity.TrueshotRequest;
import com.trueket.backend.trueshot.service.SecondMissionService;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import static org.junit.jupiter.api.Assertions.*;

class SecondMissionTests {
    @Test void allMissionInstructionsAndParametersAreConsistent() {
        assertEquals("예매 화면 옆에서 브이를 하고 찍어 주세요.", SecondMission.of(Type.GESTURE,null).instruction());
        assertNull(SecondMission.of(Type.POINT_SCREEN,null).gesture());
        for (var direction:Direction.values()) {
            var mission=SecondMission.of(Type.GESTURE_POSITION,direction);
            assertEquals("Victory",mission.gesture());
            assertFalse(mission.instruction().contains("엄지"));
        }
        assertThrows(IllegalArgumentException.class,()->SecondMission.of(Type.GESTURE_POSITION,null));
        assertThrows(IllegalArgumentException.class,()->SecondMission.of(Type.GESTURE,Direction.LEFT));
    }

    @Test void generationUsesOnlyConfirmedMissionTypesAndVictory() {
        var service=new SecondMissionService();
        for (int i=0;i<30;i++) {
            var mission=service.generate();
            assertEquals(mission,SecondMission.of(mission.type(),mission.position()));
            assertTrue(mission.gesture()==null || mission.gesture().equals("Victory"));
        }
    }

    @Test void revealedMissionStoresTypePositionAndInstruction() {
        var now=OffsetDateTime.now();
        var request=TrueshotRequest.create(null,"title","AB12","token",now);
        request.start(now);
        request.firstPhotoAccepted(now);
        var mission=SecondMission.of(Type.GESTURE_POSITION,Direction.ABOVE);
        request.revealSecondInstruction(mission,now);
        assertEquals(mission,request.getMission2());
        assertEquals(mission.instruction(),request.getInstruction2());
        assertEquals(Type.GESTURE_POSITION,request.getMission2Type());
        assertEquals(Direction.ABOVE,request.getMission2Position());
        assertEquals(now.plusMinutes(1),request.getDeadline2());
    }
}
