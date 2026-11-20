package com.trueket.backend.trueshot;

import com.trueket.backend.trueshot.dto.SecondMission;
import com.trueket.backend.trueshot.dto.TrueshotDtos.RequestDetail;
import com.trueket.backend.trueshot.dto.TrueshotDtos.SellerResult;
import com.trueket.backend.trueshot.entity.TrueshotRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrueshotRecoveryTests {
    private final OffsetDateTime start=OffsetDateTime.parse("2026-11-16T06:00:00Z");

    private TrueshotRequest request() {
        return TrueshotRequest.create(null,"인증","AB12","test-token",start);
    }

    @Test void oldCompletedRecordsDoNotInventSharingEndReason() {
        var request=request();
        request.start(start);
        request.firstPhotoAccepted(start.plusSeconds(10));
        request.revealSecondInstruction(SecondMission.of(SecondMission.Type.GESTURE,null),start.plusSeconds(20));
        request.secondPhotoReceived(start.plusSeconds(30),Duration.ofSeconds(60));
        request.finishJudgment(true,true,List.of(),start.plusSeconds(40));
        request.finishSharing(false,start.plusSeconds(50));
        org.springframework.test.util.ReflectionTestUtils.setField(request,"judgmentResult",null);
        assertNull(request.shareEndReason(start.plusHours(1)));
        assertNull(RequestDetail.from(request,start.plusHours(1)).shareEndReason());
    }

    @Test void refreshUsesOriginalFirstPhotoDeadlineAndServerTime() {
        var request=request();
        request.start(start);
        var first=SellerResult.from(request,start.plusMinutes(1),false);
        var refreshed=SellerResult.from(request,start.plusSeconds(90),false);
        assertEquals(start.plusMinutes(3),first.activeDeadline());
        assertEquals(first.activeDeadline(),refreshed.activeDeadline());
        assertEquals(120_000L,first.remainingMillis());
        assertEquals(90_000L,refreshed.remainingMillis());
        assertEquals(start.plusSeconds(90),refreshed.serverTime());
    }

    @Test void firstPhotoTimeoutIsStableBeforeAndAfterWorkerAndOverallExpiry() {
        var request=request();
        request.start(start);
        var before=SellerResult.from(request,start.plusMinutes(3),false);
        request.failForPhotoTimeout(1);
        var after=SellerResult.from(request,start.plusMinutes(31),false);
        assertEquals("FIRST_PHOTO_TIMEOUT",before.failureCode());
        assertEquals(before.failureCode(),after.failureCode());
        assertEquals("FAILED",after.phase().name());
        assertNull(after.activeDeadline());
        assertNull(after.remainingMillis());
        assertNull(after.pollAfterMillis());
        assertFalse(after.canShare());
        assertTrue(after.message().contains("새 링크"));
        assertEquals("FIRST_PHOTO_TIMEOUT",RequestDetail.from(request,start.plusMinutes(31)).failureCode());
    }

    @Test void secondPhotoTimeoutDoesNotBecomeGenericStageError() {
        var request=request();
        request.start(start);
        request.firstPhotoAccepted(start.plusSeconds(10));
        request.revealSecondInstruction(SecondMission.of(SecondMission.Type.GESTURE,null),start.plusSeconds(20));
        var before=SellerResult.from(request,start.plusSeconds(80),false);
        request.failForPhotoTimeout(2);
        var after=SellerResult.from(request,start.plusSeconds(90),false);
        assertEquals("SECOND_PHOTO_TIMEOUT",before.failureCode());
        assertEquals(before.failureCode(),after.failureCode());
        assertNull(after.activeDeadline());
    }

    @Test void activeTimerTracksEachStageAndStopsAfterCompletion() {
        var request=request();
        assertEquals(request.getExpiresAt(),SellerResult.from(request,start,false).activeDeadline());
        request.start(start);
        request.firstPhotoAccepted(start.plusSeconds(10));
        assertEquals(request.getExpiresAt(),SellerResult.from(request,start.plusSeconds(10),false).activeDeadline());
        request.revealSecondInstruction(SecondMission.of(SecondMission.Type.GESTURE,null),start.plusSeconds(20));
        assertEquals(start.plusSeconds(80),SellerResult.from(request,start.plusSeconds(20),false).activeDeadline());
        request.secondPhotoReceived(start.plusSeconds(30),Duration.ofSeconds(60));
        var judging=SellerResult.from(request,start.plusSeconds(40),false);
        assertEquals(start.plusSeconds(90),judging.activeDeadline());
        assertEquals(50_000L,judging.remainingMillis());
        assertEquals(2000L,judging.pollAfterMillis());
        request.finishJudgment(true,true,List.of(),start.plusSeconds(50));
        assertEquals(request.getExpiresAt(),SellerResult.from(request,start.plusSeconds(50),true).activeDeadline());
        request.finishSharing(true,start.plusSeconds(60));
        var completed=SellerResult.from(request,start.plusSeconds(61),true);
        assertNull(completed.activeDeadline());
        assertNull(completed.remainingMillis());
        assertNull(completed.pollAfterMillis());
        assertNull(completed.failureCode());
    }

    @Test void overallDeadlineCapsSecondTimerAndFailureDoesNotRequireWorker() {
        var request=request();
        request.start(start.plusMinutes(29));
        request.firstPhotoAccepted(start.plusMinutes(29).plusSeconds(10));
        request.revealSecondInstruction(SecondMission.of(SecondMission.Type.GESTURE,null),start.plusMinutes(29).plusSeconds(50));
        var result=SellerResult.from(request,start.plusMinutes(29).plusSeconds(55),false);
        assertEquals(request.getExpiresAt(),result.activeDeadline());
        assertEquals(5000L,result.remainingMillis());
        var expired=SellerResult.from(request,request.getExpiresAt(),false);
        assertEquals("REQUEST_TIMEOUT",expired.failureCode());
        assertNull(expired.activeDeadline());
        request.failForRequestTimeout();
        assertEquals(expired.failureCode(),SellerResult.from(request,start.plusHours(1),false).failureCode());
    }

    @Test void missionFailureRemainsDistinctFromTimeoutAfterExpiry() {
        var request=request();
        request.start(start);
        request.firstPhotoAccepted(start);
        request.revealSecondInstruction(SecondMission.of(SecondMission.Type.GESTURE,null),start);
        request.secondPhotoReceived(start,Duration.ofSeconds(60));
        request.finishJudgment(false,false,List.of("미션 불일치"),start.plusSeconds(1));
        var result=SellerResult.from(request,start.plusHours(1),false);
        assertEquals("VERIFICATION_FAILED",result.failureCode());
        assertEquals("미션 불일치",result.message());
        assertNull(result.remainingMillis());
    }

    @Test void existingFailedRecordsWithoutStoredCodeStillRecoverTimeout() {
        var request=request();
        request.start(start);
        request.failForPhotoTimeout(1);
        org.springframework.test.util.ReflectionTestUtils.setField(request,"judgmentResult",null);
        assertEquals("FIRST_PHOTO_TIMEOUT",SellerResult.from(request,start.plusMinutes(31),false).failureCode());
    }
}
