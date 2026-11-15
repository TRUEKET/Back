package com.trueket.backend.trueshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.trueshot.dto.SecondMission;
import com.trueket.backend.trueshot.dto.TrueshotChecks.Outcome;
import com.trueket.backend.trueshot.dto.TrueshotDtos.SellerResult;
import com.trueket.backend.trueshot.entity.TrueshotRequest;
import com.trueket.backend.trueshot.entity.enums.TrueshotStatus;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.OffsetDateTime;
import static org.junit.jupiter.api.Assertions.*;

class TrueshotJudgmentWaitTests {
    private final OffsetDateTime received=OffsetDateTime.parse("2026-11-15T10:00:00Z");

    @Test void storedDeadlineUsesConfiguredWaitAndCannotBeExtendedByRetries() {
        var request=waiting(received.minusMinutes(5));
        request.secondPhotoReceived(received,Duration.ofSeconds(20));
        assertEquals(received.plusSeconds(20),request.getJudgmentDeadline());
        assertFalse(request.judgmentTimedOut(received.plusSeconds(19)));
        assertTrue(request.judgmentTimedOut(received.plusSeconds(20)));
        var snapshot=new ObjectMapper().createObjectNode().put("passed",true);
        request.storeJudgment(snapshot);
        assertEquals(received.plusSeconds(20),request.getJudgmentDeadline());
    }

    @Test void totalRequestDeadlineTakesPrecedenceAndInvalidWaitDoesNotChangePhase() {
        var request=waiting(received.minusMinutes(30).plusSeconds(5));
        assertThrows(IllegalArgumentException.class,()->request.secondPhotoReceived(received,Duration.ZERO));
        request.secondPhotoReceived(received,Duration.ofSeconds(60));
        assertEquals(received.plusSeconds(5),request.getJudgmentDeadline());
    }

    @Test void pollingStopsAtDeadlineBeforeBackgroundWorkerRuns() {
        var request=waiting(received.minusMinutes(5));
        request.secondPhotoReceived(received,Duration.ofSeconds(60));
        var pending=SellerResult.from(request,received.plusSeconds(10),false);
        assertEquals(2000L,pending.pollAfterMillis());
        assertTrue(pending.message().contains("지연"));
        var timedOut=SellerResult.from(request,received.plusSeconds(60),false);
        assertEquals(TrueshotStatus.FAILED,timedOut.status());
        assertEquals("ANALYSIS_TIMEOUT",timedOut.failureCode());
        assertNull(timedOut.pollAfterMillis());
        assertEquals(Outcome.NOT_CHECKED,timedOut.checks().get(2).status());
        assertFalse(timedOut.failReasons().isEmpty());
    }

    @Test void timeoutDiscardsProvisionalPassWithoutInventingQualityFailure() {
        var request=waiting(received.minusMinutes(5));
        request.secondPhotoReceived(received,Duration.ofSeconds(60));
        var snapshot=new ObjectMapper().createObjectNode().put("passed",true).put("secondPhotoClear",true);
        snapshot.putObject("missionCheck").put("status","MATCHED");
        request.storeJudgment(snapshot);
        request.failForJudgmentTimeout(received.plusSeconds(60));
        assertEquals(TrueshotStatus.FAILED,request.getStatus());
        assertFalse(request.getJudgmentResult().path("passed").asBoolean());
        assertEquals(received.plusSeconds(60),request.getJudgedAt());
        var result=SellerResult.from(request,received.plusSeconds(61),false);
        assertEquals("ANALYSIS_TIMEOUT",result.failureCode());
        assertEquals(Outcome.PASSED,result.checks().get(0).status());
        assertEquals(Outcome.NOT_CHECKED,result.checks().get(2).status());
        assertEquals(Outcome.NOT_CHECKED,result.checks().get(3).status());
    }

    private TrueshotRequest waiting(OffsetDateTime created) {
        var request=TrueshotRequest.create(null,"title","AB12","token",created);
        request.start(created); request.firstPhotoAccepted(created);
        request.revealSecondInstruction(SecondMission.of(SecondMission.Type.GESTURE,null),created);
        return request;
    }
}
