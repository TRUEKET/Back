package com.trueket.backend.trueshot;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.trueshot.dto.SecondMission;
import com.trueket.backend.trueshot.dto.SecondMission.*;
import com.trueket.backend.trueshot.entity.TrueshotRequest;
import com.trueket.backend.trueshot.repository.TrueshotRequestRepository;
import com.trueket.backend.trueshot.service.SecondMissionService;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
        var service=new SecondMissionService(mock(TrueshotRequestRepository.class));
        for (int i=0;i<30;i++) {
            var mission=service.generate();
            assertEquals(mission,SecondMission.of(mission.type(),mission.position()));
            assertTrue(mission.gesture()==null || mission.gesture().equals("Victory"));
        }
    }

    @Test void currentMissionUsesPersistedValueAndRejectsExpiredOrWrongStage() {
        var repository=mock(TrueshotRequestRepository.class);
        var service=new SecondMissionService(repository);
        var now=OffsetDateTime.now();
        var request=TrueshotRequest.create(null,"title","AB12","token",now);
        when(repository.findByToken("token")).thenReturn(Optional.of(request));
        assertThrows(ApiException.class,()->service.current("token"));
        request.start(now);
        request.firstPhotoAccepted(now);
        var mission=SecondMission.of(Type.GESTURE_POSITION,Direction.ABOVE);
        request.revealSecondInstruction(mission,now);
        assertEquals(mission,service.current("token"));
        assertEquals(mission.instruction(),request.getInstruction2());
        request.secondPhotoReceived(now);
        assertThrows(ApiException.class,()->service.current("token"));
        var expired=TrueshotRequest.create(null,"title","AB12","token",now.minusMinutes(3));
        expired.start(now.minusMinutes(3));
        expired.firstPhotoAccepted(now.minusMinutes(2));
        expired.revealSecondInstruction(mission,now.minusMinutes(2));
        when(repository.findByToken("token")).thenReturn(Optional.of(expired));
        assertThrows(ApiException.class,()->service.current("token"));
    }
}
