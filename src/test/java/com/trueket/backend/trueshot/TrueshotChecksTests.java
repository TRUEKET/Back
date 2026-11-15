package com.trueket.backend.trueshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.trueshot.dto.TrueshotChecks;
import com.trueket.backend.trueshot.dto.TrueshotChecks.*;
import com.trueket.backend.trueshot.dto.TrueshotDtos.SellerResult;
import com.trueket.backend.trueshot.dto.SecondMission;
import com.trueket.backend.trueshot.entity.TrueshotRequest;
import com.trueket.backend.trueshot.entity.enums.TrueshotStatus;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TrueshotChecksTests {
    private final OffsetDateTime now=OffsetDateTime.now();
    private final ObjectMapper json=new ObjectMapper();

    @Test void firstPhotoReportsQualityAndCodeIndependently() {
        var wrongCode=TrueshotChecks.first(true,true,false);
        assertEquals(Outcome.PASSED,wrongCode.get(0).status());
        assertEquals(Outcome.FAILED,wrongCode.get(1).status());
        var blurry=TrueshotChecks.first(false,true,true);
        assertEquals(Outcome.FAILED,blurry.get(0).status());
        assertEquals(Outcome.PASSED,blurry.get(1).status());
        assertEquals(Outcome.FAILED,TrueshotChecks.first(true,false,true).get(1).status());
    }

    @Test void timedOutPhotoReturnsFailureWithoutInventingAnalysisChecks() {
        var request=request();
        request.start(now.minusMinutes(4));
        var result=SellerResult.from(request,now,false);
        assertEquals(TrueshotStatus.FAILED,result.status());
        assertEquals(List.of("첫 번째 사진이 시간 내에 전송되지 않음"),result.failReasons());
        assertTrue(result.checks().stream().allMatch(check -> check.status()==Outcome.NOT_CHECKED));
        var second=request();
        second.start(now.minusMinutes(3)); second.firstPhotoAccepted(now.minusMinutes(2));
        second.revealSecondInstruction(SecondMission.of(SecondMission.Type.GESTURE,null),now.minusMinutes(2));
        var timedOut=SellerResult.from(second,now,false);
        assertEquals(TrueshotStatus.FAILED,timedOut.status());
        assertEquals(List.of("두 번째 사진이 시간 내에 전송되지 않음"),timedOut.failReasons());
        assertEquals(Outcome.PASSED,timedOut.checks().get(0).status());
        assertEquals(Outcome.NOT_CHECKED,timedOut.checks().get(2).status());
    }

    @Test void pendingMaskingDoesNotExposeProvisionalMissionAsFinalPass() {
        var request=judging();
        request.storeJudgment(snapshot());
        var result=SellerResult.from(request,now,false);
        assertEquals(Outcome.PASSED,result.checks().get(0).status());
        assertEquals(Outcome.PENDING,result.checks().get(2).status());
        assertEquals(Outcome.PENDING,result.checks().get(3).status());
        assertTrue(result.failReasons().isEmpty());
    }

    @Test void legacyResultWithoutQualityObservationIsNotMarkedAsPassed() {
        var request=judging();
        request.storeJudgment(snapshot());
        request.finishJudgment(true,false,List.of(),now);
        var checks=SellerResult.from(request,now,false).checks();
        assertEquals(Outcome.NOT_CHECKED,checks.get(2).status());
        assertEquals(Outcome.PASSED,checks.get(3).status());
    }

    private TrueshotRequest request() { return TrueshotRequest.create(null,"title","AB12","token",now.minusMinutes(5)); }
    private TrueshotRequest judging() {
        var request=request(); request.start(now); request.firstPhotoAccepted(now);
        request.revealSecondInstruction(SecondMission.of(SecondMission.Type.GESTURE,null),now);
        request.secondPhotoReceived(now,java.time.Duration.ofSeconds(60));
        return request;
    }
    private com.fasterxml.jackson.databind.JsonNode snapshot() {
        var snapshot=json.createObjectNode(); snapshot.put("passed",true);
        snapshot.putObject("missionCheck").put("status","MATCHED");
        return snapshot;
    }
}
