package com.trueket.backend.trueshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.global.storage.SupabaseStorageDeletion;
import com.trueket.backend.trueshot.dto.SecondMission;
import com.trueket.backend.trueshot.dto.TrueshotMaskDtos.Box;
import com.trueket.backend.trueshot.entity.*;
import com.trueket.backend.trueshot.repository.*;
import com.trueket.backend.trueshot.service.*;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TrueshotJudgmentFlowTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TrueshotRequestRepository requests;
    @Autowired TrueshotPhotoRepository photos;
    @Autowired JdbcTemplate jdbc;
    @Autowired TrueshotExpiryJob expiry;
    @Autowired TrueshotJudgmentService judgments;
    @MockitoBean TrueshotOriginalStore originals;
    @MockitoBean TrueshotMaskStorage storage;
    @MockitoBean TrueshotMaskDetector masks;
    @MockitoBean BookingScreenDetector screens;
    @MockitoBean SupabaseStorageDeletion deletion;
    private final ObjectMapper json=new ObjectMapper();
    private byte[] image;

    @BeforeEach void dependencies() throws Exception {
        var output=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(400,400,BufferedImage.TYPE_INT_RGB),"jpg",output);
        image=output.toByteArray();
        when(storage.original(anyString())).thenReturn(image);
        when(storage.masked(anyString())).thenReturn(image);
        when(screens.detect(any())).thenReturn(new BookingScreenDetector.Detection(true,true,.3,.3,.4,.4,"원문은 저장하지 않음",true));
        when(masks.detect(any())).thenReturn(new TrueshotMaskDetector.Detection(true,List.of(new Box(.1,.1,.1,.1))));
    }

    @Test void successfulJudgmentRequiresConsentBeforeBuyerCanReadMaskedPhotos() throws Exception {
        var f=fixture(true);
        submit(f,analysis(true)).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("SHARE_WAITING"));
        var saved=requests.findById(f.id()).orElseThrow();
        assertEquals("PASSED",saved.getStatus().name());
        assertNotNull(saved.getJudgedAt());
        assertFalse(saved.getJudgmentResult().toString().contains("원문"));
        assertTrue(photos.findByRequestId(f.id()).stream().allMatch(photo -> photo.getOriginalStorageKey()==null));
        mvc.perform(get(f.path()+"/result").session(f.seller().session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.canShare").value(true)).andExpect(jsonPath("$.judgmentResult").doesNotExist())
                .andExpect(jsonPath("$.checks.length()").value(4))
                .andExpect(jsonPath("$.checks[0].status").value("PASSED"))
                .andExpect(jsonPath("$.checks[1].status").value("PASSED"))
                .andExpect(jsonPath("$.checks[2].status").value("PASSED"))
                .andExpect(jsonPath("$.checks[3].status").value("PASSED"))
                .andExpect(jsonPath("$.failReasons").isEmpty());
        buyerDetail(f).andExpect(jsonPath("$.photos").isEmpty()).andExpect(jsonPath("$.judgmentResult.passed").value(true));
        buyerPhoto(f,1,f.buyer()).andExpect(status().isNotFound());
        mvc.perform(get(f.path()+"/preview/2").session(f.seller().session())).andExpect(status().isOk());
        share(f,true,"[{\"x\":0.6,\"y\":0.6,\"width\":0.1,\"height\":0.1}]")
                .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("COMPLETED"));
        var sharedAt=requests.findById(f.id()).orElseThrow().getPhotosSharedAt();
        assertNotNull(sharedAt);
        buyerDetail(f).andExpect(jsonPath("$.photos.length()").value(2));
        buyerPhoto(f,1,f.buyer()).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        buyerPhoto(f,2,f.buyer()).andExpect(status().isOk());
        buyerPhoto(f,2,buyer()).andExpect(status().isNotFound());
        mvc.perform(get("/trueshot/"+f.id()+"/photos/1")).andExpect(status().isUnauthorized());
        share(f,true,"[]").andExpect(status().isOk());
        assertTrue(sharedAt.isEqual(requests.findById(f.id()).orElseThrow().getPhotosSharedAt()));
        verify(screens,times(1)).detect(any());
        submit(f,analysis(true)).andExpect(status().isConflict());
    }

    @Test void missionMismatchStoresBuyerReasonsButSellerReceivesOnlySummary() throws Exception {
        var f=fixture(true);
        submit(f,analysis(false)).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("FAILED"));
        buyerDetail(f).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failReasons[0]").exists()).andExpect(jsonPath("$.photos").isEmpty());
        mvc.perform(get(f.path()+"/result").session(f.seller().session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.failReasons[0]").exists()).andExpect(jsonPath("$.canShare").value(false));
        share(f,true,"[]").andExpect(status().isConflict());
        assertNotNull(requests.findById(f.id()).orElseThrow().getJudgedAt());
        verify(masks,never()).detect(any());
    }

    @Test void modelOutageKeepsOneSubmissionAndRetriesStoredPhotoWithoutNewUpload() throws Exception {
        var f=fixture(true);
        when(screens.detect(any())).thenThrow(unavailable());
        submit(f,analysis(true)).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("JUDGING"));
        mvc.perform(get(f.path()+"/result").session(f.seller().session()))
                .andExpect(jsonPath("$.checks[2].status").value("PENDING"))
                .andExpect(jsonPath("$.checks[3].status").value("PENDING"))
                .andExpect(jsonPath("$.failReasons").isEmpty());
        assertNotNull(photos.findByRequestIdAndNumber(f.id(),(short)2).orElseThrow().getHandAnalysis());
        assertNull(requests.findById(f.id()).orElseThrow().getJudgedAt());
        expiry.retryJudgments();
        assertNull(requests.findById(f.id()).orElseThrow().getJudgedAt());
        doReturn(new BookingScreenDetector.Detection(true,true,.3,.3,.4,.4,"",true)).when(screens).detect(any());
        expiry.retryJudgments();
        mvc.perform(get(f.path()+"/result").session(f.seller().session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"));
        expiry.retryJudgments();
        verify(originals,times(1)).upload(anyString(),any(),anyString());
        assertEquals(2,photos.findByRequestId(f.id()).size());
    }

    @Test void maskOutagePreservesJudgmentSnapshotAndSkipsRepeatedScreenAnalysis() throws Exception {
        var f=fixture(true);
        when(masks.detect(any())).thenThrow(unavailable());
        submit(f,analysis(true)).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("JUDGING"));
        assertNotNull(requests.findById(f.id()).orElseThrow().getJudgmentResult());
        doReturn(new TrueshotMaskDetector.Detection(true,List.of(new Box(.1,.1,.1,.1)))).when(masks).detect(any());
        expiry.retryJudgments();
        mvc.perform(get(f.path()+"/result").session(f.seller().session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("SHARE_WAITING"));
        verify(screens,times(1)).detect(any());
    }

    @Test void judgmentWaitExpiresForPollingAndWorkerWithoutAnotherModelCall() throws Exception {
        var f=fixture(true);
        when(screens.detect(any())).thenThrow(unavailable());
        submit(f,analysis(true)).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("JUDGING"))
                .andExpect(jsonPath("$.deadline").isString());
        var request=requests.findById(f.id()).orElseThrow();
        assertEquals(60,java.time.Duration.between(request.getSecondReceivedAt(),request.getJudgmentDeadline()).getSeconds());
        expireJudgmentDeadline(f);
        mvc.perform(get(f.path()+"/result").session(f.seller().session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("ANALYSIS_TIMEOUT"))
                .andExpect(jsonPath("$.pollAfterMillis").isEmpty());
        buyerDetail(f).andExpect(jsonPath("$.status").value("FAILED"));
        expiry.retryJudgments();
        var failed=requests.findById(f.id()).orElseThrow();
        assertEquals("FAILED",failed.getStatus().name());
        assertNotNull(failed.getJudgedAt());
        assertFalse(failed.getJudgmentResult().path("passed").asBoolean());
        assertTrue(photos.findByRequestId(f.id()).stream().allMatch(photo -> photo.getOriginalStorageKey()==null));
        verify(screens,times(1)).detect(any());
    }

    @Test void maskingOutageCannotKeepProvisionalPassPastDeadline() throws Exception {
        var f=fixture(true);
        when(masks.detect(any())).thenThrow(unavailable());
        submit(f,analysis(true)).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("JUDGING"));
        assertTrue(requests.findById(f.id()).orElseThrow().getJudgmentResult().path("passed").asBoolean());
        expireJudgmentDeadline(f);
        expiry.retryJudgments();
        var failed=requests.findById(f.id()).orElseThrow();
        assertEquals("FAILED",failed.getStatus().name());
        assertFalse(failed.getJudgmentResult().path("passed").asBoolean());
        mvc.perform(get(f.path()+"/result").session(f.seller().session()))
                .andExpect(jsonPath("$.failureCode").value("ANALYSIS_TIMEOUT"))
                .andExpect(jsonPath("$.checks[2].status").value("NOT_CHECKED"));
        verify(screens,times(1)).detect(any());
        verify(masks,times(1)).detect(any());
    }

    @Test void lateModelSuccessOrErrorReturnsTimeoutInsteadOfAnotherLoadingResponse() throws Exception {
        for (boolean modelError:List.of(false,true)) {
            var f=fixture(true);
            doAnswer(call -> {
                // 현재 판정 트랜잭션이 가진 마감 시각을 지나도록 만들어 늦게 도착한 응답을 검증한다.
                expireJudgmentDeadline(f);
                if (modelError) throw unavailable();
                return new BookingScreenDetector.Detection(true,true,.3,.3,.4,.4,"",true);
            }).when(screens).detect(any());
            submit(f,analysis(true)).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("FAILED"))
                    .andExpect(jsonPath("$.failReasons[0]").exists());
            assertEquals("ANALYSIS_TIMEOUT",requests.findById(f.id()).orElseThrow().getJudgmentResult().path("failureCode").asText());
        }
    }

    @Test void incompleteMaskAllowsResultOnlyAndNeverExposesOriginal() throws Exception {
        var f=fixture(true);
        when(masks.detect(any())).thenReturn(new TrueshotMaskDetector.Detection(false,List.of()));
        submit(f,analysis(true)).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("COMPLETED"));
        assertEquals("PASSED",requests.findById(f.id()).orElseThrow().getStatus().name());
        mvc.perform(get(f.path()+"/result").session(f.seller().session())).andExpect(jsonPath("$.canShare").value(false));
        buyerDetail(f).andExpect(jsonPath("$.photos").isEmpty());
        buyerPhoto(f,1,f.buyer()).andExpect(status().isNotFound());
        buyerPhoto(f,2,f.buyer()).andExpect(status().isNotFound());
        assertTrue(photos.findByRequestId(f.id()).stream().allMatch(photo -> photo.getOriginalStorageKey()==null));
    }

    @Test void blurrySecondPhotoFailsEvenWhenMissionMatchesAndChecksSurviveReload() throws Exception {
        var f=fixture(true);
        when(screens.detect(any())).thenReturn(new BookingScreenDetector.Detection(true,true,.3,.3,.4,.4,"",false));
        submit(f,analysis(true)).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("FAILED"))
                .andExpect(jsonPath("$.checks[0].photoNumber").value(2))
                .andExpect(jsonPath("$.checks[0].status").value("FAILED"))
                .andExpect(jsonPath("$.checks[1].status").value("PASSED"))
                .andExpect(jsonPath("$.failReasons[0]").exists());
        mvc.perform(get(f.path()+"/result").session(f.seller().session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.checks[0].status").value("PASSED"))
                .andExpect(jsonPath("$.checks[1].status").value("PASSED"))
                .andExpect(jsonPath("$.checks[2].status").value("FAILED"))
                .andExpect(jsonPath("$.checks[3].status").value("PASSED"));
        assertFalse(requests.findById(f.id()).orElseThrow().getJudgmentResult().path("secondPhotoClear").asBoolean());
        verify(masks,never()).detect(any());
    }

    @Test void originalDeletionFailureKeepsResultAndKeysForScheduledRetry() throws Exception {
        var f=fixture(true);
        doThrow(unavailable()).when(deletion).deleteAll(anyList());
        submit(f,analysis(true)).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("SHARE_WAITING"));
        assertNotNull(requests.findById(f.id()).orElseThrow().getJudgedAt());
        assertTrue(photos.findByRequestId(f.id()).stream().allMatch(photo -> photo.getOriginalStorageKey()!=null));
        doNothing().when(deletion).deleteAll(anyList());
        expiry.expireRequests();
        assertTrue(photos.findByRequestId(f.id()).stream().allMatch(photo -> photo.getOriginalStorageKey()==null
                && photo.getOriginalDeletedAt()!=null));
        verify(screens,times(1)).detect(any());
    }

    @Test void declinedAndExpiredShareWindowsDoNotMakePhotosPublic() throws Exception {
        var f=fixture(true);
        submit(f,analysis(true)).andExpect(status().isOk());
        share(f,false,"[]").andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("COMPLETED"));
        assertNull(requests.findById(f.id()).orElseThrow().getPhotosSharedAt());
        buyerPhoto(f,1,f.buyer()).andExpect(status().isNotFound());
        var expired=fixture(true);
        submit(expired,analysis(true)).andExpect(status().isOk());
        jdbc.update("update trueshot_requests set expires_at=? where id=?",OffsetDateTime.now().minusSeconds(1),expired.id());
        share(expired,true,"[]").andExpect(status().isGone());
        assertEquals("COMPLETED",requests.findById(expired.id()).orElseThrow().getPhase().name());
        assertNull(requests.findById(expired.id()).orElseThrow().getPhotosSharedAt());
    }

    @Test void invalidOrMissingAnalysisAndWrongSellerDoNotConsumeSubmission() throws Exception {
        var f=fixture(true);
        mvc.perform(multipart(f.path()+"/photo").file(photo()).param("n","2").session(f.seller().session())
                .header(f.seller().csrf().getHeaderName(),f.seller().csrf().getToken())).andExpect(status().isBadRequest());
        for (String data:List.of(analysis(true).replace("imageWidth\":400","imageWidth\":500"),
                analysis(true).replace("\"x\":20","\"x\":-1"),analysis(true).replace("\"x\":20","\"x\":401"),
                analysis(true).replace("\"y\":200","\"y\":401"),analysis(true).replace("0.96","1.1"),
                analysis(true).replace("Victory","V"),analysis(true).replace(",\"confidence\":0.96",""),
                "{\"imageWidth\":400,\"imageHeight\":400,\"hands\":null}",
                "{\"imageWidth\":400,\"imageHeight\":400,\"hands\":[null]}")) {
            submit(f,data).andExpect(status().isBadRequest());
        }
        assertFalse(photos.existsByRequestIdAndNumber(f.id(),(short)2));
        mvc.perform(get(f.path()+"/result").session(f.buyer().session())).andExpect(status().isForbidden());
        mvc.perform(post(f.path()+"/share").session(f.seller().session()).contentType("application/json")
                .content("{\"sharePhotos\":true,\"boxes\":[]}")).andExpect(status().isForbidden());
        mvc.perform(multipart(f.path()+"/photo").file(photo()).param("n","2")
                .file(new MockMultipartFile("analysis","analysis.json","application/json",analysis(true).getBytes(StandardCharsets.UTF_8)))
                .session(f.seller().session())).andExpect(status().isForbidden());
    }

    @Test void submissionRejectsExifOversizeAndUnreadablePhotosBeforeSavingOrAnalyzing() throws Exception {
        var f=fixture(true);
        var jpegExif=new ByteArrayOutputStream();
        jpegExif.write(image,0,2);
        jpegExif.write(new byte[]{(byte)255,(byte)225,0,8,'E','x','i','f',0,0});
        jpegExif.write(image,2,image.length-2);
        submit(f,jpegExif.toByteArray(),analysis(true)).andExpect(status().isBadRequest());
        var pngOutput=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(400,400,BufferedImage.TYPE_INT_RGB),"png",pngOutput);
        byte[] png=pngOutput.toByteArray();
        var pngExif=new ByteArrayOutputStream();
        pngExif.write(png,0,33);
        var chunk=new java.io.DataOutputStream(pngExif);
        byte[] typeAndPayload={'e','X','I','f','I','I',42,0,8,0,0,0};
        chunk.writeInt(8); chunk.write(typeAndPayload);
        var crc=new java.util.zip.CRC32(); crc.update(typeAndPayload); chunk.writeInt((int)crc.getValue());
        pngExif.write(png,33,png.length-33);
        submit(f,pngExif.toByteArray(),analysis(true)).andExpect(status().isBadRequest());
        submit(f,new byte[TrueshotImageInput.MAX_BYTES+1],analysis(true)).andExpect(status().isPayloadTooLarge());
        submit(f,new byte[]{1,2,3},analysis(true)).andExpect(status().isBadRequest());
        assertFalse(photos.existsByRequestIdAndNumber(f.id(),(short)2));
        verifyNoInteractions(screens,originals);
    }

    @Test void standaloneHandAnalysisRouteHasBeenRemoved() throws Exception {
        var buyer=buyer();
        mvc.perform(post("/hand-analyze").session(buyer.session())
                .header(buyer.csrf().getHeaderName(),buyer.csrf().getToken())).andExpect(status().isNotFound());
    }

    @Test void manualJudgmentRouteHasBeenRemovedWithoutConsumingSubmission() throws Exception {
        var f=fixture(true);
        postSeller(f,"/judge",null).andExpect(status().isNotFound());
        assertEquals("SECOND_WAITING",requests.findById(f.id()).orElseThrow().getPhase().name());
        assertFalse(photos.existsByRequestIdAndNumber(f.id(),(short)2));
    }

    @Test void noHandsOrUncertainScreenNeverPassesAndFailedDeletionCanBeRetried() throws Exception {
        var noHands=fixture(true);
        doThrow(unavailable()).when(deletion).deleteAll(anyList());
        submit(noHands,"{\"imageWidth\":400,\"imageHeight\":400,\"hands\":[]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("FAILED"))
                .andExpect(jsonPath("$.checks[0].status").value("NOT_CHECKED"))
                .andExpect(jsonPath("$.checks[1].status").value("FAILED"));
        buyerDetail(noHands).andExpect(jsonPath("$.judgmentResult.analysisStatus").value("NO_HANDS"));
        assertTrue(photos.findByRequestId(noHands.id()).stream().allMatch(photo -> photo.getOriginalStorageKey()!=null));
        doNothing().when(deletion).deleteAll(anyList());
        expiry.expireRequests();
        assertTrue(photos.findByRequestId(noHands.id()).stream().allMatch(photo -> photo.getOriginalStorageKey()==null));
        var uncertain=fixture(true);
        when(screens.detect(any())).thenReturn(new BookingScreenDetector.Detection(true,false,.3,.3,.4,.4,"",true));
        submit(uncertain,analysis(true)).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("FAILED"));
        buyerDetail(uncertain).andExpect(jsonPath("$.judgmentResult.missionCheck.status").value("UNDETERMINED"));
    }

    @Test void firstMaskFailureKeepsOnlyResultAndExpiredJudgingCannotPass() throws Exception {
        var resultOnly=fixture(false);
        submit(resultOnly,analysis(true)).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("COMPLETED"));
        buyerPhoto(resultOnly,2,resultOnly.buyer()).andExpect(status().isNotFound());
        var expired=fixture(true);
        when(screens.detect(any())).thenThrow(unavailable());
        submit(expired,analysis(true)).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("JUDGING"));
        jdbc.update("update trueshot_requests set expires_at=? where id=?",OffsetDateTime.now().minusSeconds(1),expired.id());
        expiry.expireRequests();
        mvc.perform(get(expired.path()+"/result").session(expired.seller().session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        assertTrue(photos.findByRequestId(expired.id()).stream().allMatch(photo -> photo.getOriginalStorageKey()==null));
    }

    @Test void sharingWriteFailureLeavesConsentUnrecordedAndRetentionBlocksOldPhotos() throws Exception {
        var f=fixture(true);
        submit(f,analysis(true)).andExpect(status().isOk());
        String additional="[{\"x\":0.5,\"y\":0.5,\"width\":0.1,\"height\":0.1}]";
        doThrow(unavailable()).when(storage).saveMasked(anyString(),any());
        share(f,true,additional).andExpect(status().isServiceUnavailable());
        assertNull(requests.findById(f.id()).orElseThrow().getPhotosSharedAt());
        buyerPhoto(f,2,f.buyer()).andExpect(status().isNotFound());
        doNothing().when(storage).saveMasked(anyString(),any());
        share(f,true,additional).andExpect(status().isOk());
        jdbc.update("update trueshot_requests set purge_at=? where id=?",OffsetDateTime.now().minusSeconds(1),f.id());
        buyerPhoto(f,2,f.buyer()).andExpect(status().isNotFound());
        buyerDetail(f).andExpect(jsonPath("$.photos").isEmpty());
    }

    @Test void concurrentJudgmentRetriesAnalyzeOnlyOnceAfterRecovery() throws Exception {
        var f=fixture(true);
        when(screens.detect(any())).thenThrow(unavailable());
        submit(f,analysis(true)).andExpect(status().isOk());
        doReturn(new BookingScreenDetector.Detection(true,true,.3,.3,.4,.4,"",true)).when(screens).detect(any());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Void> retry=() -> {
                start.await();
                judgments.judgeById(f.id());
                return null;
            };
            var one=pool.submit(retry);
            var two=pool.submit(retry);
            start.countDown();
            one.get(15,java.util.concurrent.TimeUnit.SECONDS);
            two.get(15,java.util.concurrent.TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        verify(screens,times(2)).detect(any());
        assertEquals("PASSED",requests.findById(f.id()).orElseThrow().getStatus().name());
        assertEquals(2,photos.findByRequestId(f.id()).size());
    }

    private Fixture fixture(boolean firstMasked) throws Exception {
        var buyer=buyer();
        var now=OffsetDateTime.now();
        var request=TrueshotRequest.create(buyer.user(),"인증","AB12",UUID.randomUUID().toString(),now);
        request.start(now); request.firstPhotoAccepted(now);
        request.revealSecondInstruction(SecondMission.of(SecondMission.Type.GESTURE_POSITION,SecondMission.Direction.LEFT),now);
        request=requests.save(request);
        var photo=TrueshotPhoto.create(request,(short)1,now);
        photo.storeOriginal("trueshots/"+request.getId()+"/first.jpg");
        if (firstMasked) {
            var boxes=json.createObjectNode(); boxes.set("automatic",json.createArrayNode()); boxes.set("seller",json.createArrayNode());
            photo.maskingReady("trueshots/"+request.getId()+"/first-mask.jpg",boxes);
        } else photo.maskingFailed();
        photos.save(photo);
        var seller=browser(null);
        seller.session().setAttribute("trueshotSeller:"+request.getToken(),true);
        return new Fixture(request.getId(),"/s/"+request.getToken(),buyer,seller);
    }
    private String analysis(boolean match) {
        return "{\"imageWidth\":400,\"imageHeight\":400,\"hands\":[{\"x\":20,\"y\":200,\"gesture\":\""
                +(match?"Victory":"Open_Palm")+"\",\"confidence\":0.96}]}";
    }
    private void expireJudgmentDeadline(Fixture f) {
        var request=requests.findById(f.id()).orElseThrow();
        ((com.fasterxml.jackson.databind.node.ObjectNode)request.getJudgmentResult())
                .put("deadline",OffsetDateTime.now().minusSeconds(1).toString());
        requests.save(request);
    }
    private MockMultipartFile photo() { return new MockMultipartFile("photo","photo.jpg","image/jpeg",image); }
    private ResultActions submit(Fixture f,String data) throws Exception {
        return submit(f,image,data);
    }
    private ResultActions submit(Fixture f,byte[] bytes,String data) throws Exception {
        return mvc.perform(multipart(f.path()+"/photo").file(new MockMultipartFile("photo","photo.jpg","image/jpeg",bytes))
                .file(new MockMultipartFile("analysis","analysis.json",
                "application/json",data.getBytes(StandardCharsets.UTF_8))).param("n","2").session(f.seller().session())
                .header(f.seller().csrf().getHeaderName(),f.seller().csrf().getToken()));
    }
    private ResultActions share(Fixture f,boolean consent,String boxes) throws Exception {
        return postSeller(f,"/share","{\"sharePhotos\":"+consent+",\"boxes\":"+boxes+"}");
    }
    private ResultActions postSeller(Fixture f,String endpoint,String body) throws Exception {
        var builder=post(f.path()+endpoint).session(f.seller().session())
                .header(f.seller().csrf().getHeaderName(),f.seller().csrf().getToken());
        if (body!=null) builder.contentType("application/json").content(body);
        return mvc.perform(builder);
    }
    private ResultActions buyerDetail(Fixture f) throws Exception { return mvc.perform(get("/trueshot/"+f.id()).session(f.buyer().session())); }
    private ResultActions buyerPhoto(Fixture f,int n,Browser buyer) throws Exception {
        return mvc.perform(get("/trueshot/"+f.id()+"/photos/"+n).session(buyer.session()));
    }
    private Browser buyer() throws Exception {
        String unique=UUID.randomUUID().toString();
        return browser(users.save(User.create(unique+"@example.com","unused",unique,"unused","1234",OffsetDateTime.now())));
    }
    private Browser browser(User user) throws Exception {
        var session=new MockHttpSession();
        if (user!=null) {
            var context=SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.getId().toString(),null,List.of()));
            session.setAttribute("SPRING_SECURITY_CONTEXT",context);
        }
        var response=mvc.perform(get("/auth/csrf").session(session)).andReturn();
        return new Browser(user,session,(CsrfToken)response.getRequest().getAttribute(CsrfToken.class.getName()));
    }
    private ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"외부 서비스 오류"); }
    private record Browser(User user,MockHttpSession session,CsrfToken csrf) {}
    private record Fixture(Long id,String path,Browser buyer,Browser seller) {}
}
