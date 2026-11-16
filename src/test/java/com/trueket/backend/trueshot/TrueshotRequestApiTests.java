package com.trueket.backend.trueshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.trueshot.service.TrueshotExpiryJob;
import com.trueket.backend.trueshot.service.TrueshotFirstPhotoChecker;
import com.trueket.backend.trueshot.service.TrueshotOriginalStore;
import com.trueket.backend.trueshot.service.TrueshotMaskDetector;
import com.trueket.backend.trueshot.service.TrueshotMaskStorage;
import com.trueket.backend.trueshot.service.BookingScreenDetector;
import com.trueket.backend.trueshot.dto.TrueshotMaskDtos.Box;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.user.repository.UserRepository;
import com.trueket.backend.trueshot.repository.TrueshotRequestRepository;
import com.trueket.backend.trueshot.repository.TrueshotPhotoRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TrueshotRequestApiTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired TrueshotExpiryJob expiryJob;
    @Autowired TrueshotRequestRepository requests;
    @Autowired TrueshotPhotoRepository photos;
    @MockitoBean TrueshotFirstPhotoChecker checker;
    @MockitoBean TrueshotOriginalStore originals;
    @MockitoBean TrueshotMaskDetector maskDetector;
    @MockitoBean TrueshotMaskStorage maskStorage;
    @MockitoBean BookingScreenDetector screens;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void buyerCreatesRequestAndSellerCanStartOnlyOnce() throws Exception {
        Browser buyer = buyer();
        var created = mvc.perform(post("/trueshot").session(buyer.session())
                        .header(buyer.csrf().getHeaderName(), buyer.csrf().getToken())
                        .contentType("application/json").content("{\"title\":\"  공연 양도  \"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.title").value("공연 양도"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        JsonNode result = json.readTree(created.getResponse().getContentAsString());
        long id = result.get("id").asLong();
        String path = result.get("sellerPath").asText();
        assertTrue(path.startsWith("/s/"));
        mvc.perform(get("/trueshot/{id}", id).session(buyer.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sellerPath").value(path));
        mvc.perform(get("/trueshot").session(buyer.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").doesNotExist());
        Browser seller = anonymous();
        mvc.perform(post(path + "/start").session(seller.session())
                        .header(seller.csrf().getHeaderName(), seller.csrf().getToken())
                        .contentType("application/json").content("{\"sellerConsented\":false}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path + "/start").session(seller.session())
                        .header(seller.csrf().getHeaderName(), seller.csrf().getToken())
                        .contentType("application/json").content("{\"sellerConsented\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isString())
                .andExpect(jsonPath("$.deadline1").exists());
        mvc.perform(post(path + "/start").session(seller.session())
                        .header(seller.csrf().getHeaderName(), seller.csrf().getToken())
                        .contentType("application/json").content("{\"sellerConsented\":true}"))
                .andExpect(status().isConflict());
        mvc.perform(get(path)).andExpect(status().isConflict());
        mvc.perform(get("/trueshot/{id}", id).session(buyer.session()))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        assertNotNull(jdbc.queryForObject(
                "select seller_consented_at from trueshot_requests where id = ?", OffsetDateTime.class, id));
    }

    @Test
    void requestUsesDefaultTitleAndFourCharacterCode() throws Exception {
        Browser buyer = buyer();
        var created = mvc.perform(post("/trueshot").session(buyer.session())
                        .header(buyer.csrf().getHeaderName(), buyer.csrf().getToken())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("요청 1"))
                .andReturn();
        String code = json.readTree(created.getResponse().getContentAsString()).get("code").asText();
        assertTrue(code.matches("[A-Z0-9]{4}"));
        mvc.perform(get("/trueshot"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredRequestFailsAndOtherBuyerCannotReadIt() throws Exception {
        Browser buyer = buyer();
        var created = mvc.perform(post("/trueshot").session(buyer.session())
                        .header(buyer.csrf().getHeaderName(), buyer.csrf().getToken())
                        .contentType("application/json").content("{\"title\":\"티켓 확인\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode result = json.readTree(created.getResponse().getContentAsString());
        long id = result.get("id").asLong();
        String path = result.get("sellerPath").asText();
        mvc.perform(get("/trueshot/{id}", id).session(buyer().session()))
                .andExpect(status().isNotFound());
        jdbc.update("update trueshot_requests set expires_at = ? where id = ?",
                OffsetDateTime.now().minusSeconds(1), id);
        mvc.perform(get(path)).andExpect(status().isGone());
        mvc.perform(get("/trueshot/{id}", id).session(buyer.session()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failReasons[0]").value("30분 안에 시작하지 않음"));
        expiryJob.expireRequests();
        assertEquals("FAILED", jdbc.queryForObject(
                "select status from trueshot_requests where id = ?", String.class, id));
        assertNotNull(jdbc.queryForObject(
                "select purge_at from trueshot_requests where id = ?", OffsetDateTime.class, id));
    }

    @Test
    void firstPhotoCanBeRetriedAndSecondNeedsMasking() throws Exception {
        Browser buyer = buyer();
        var created = mvc.perform(post("/trueshot").session(buyer.session())
                        .header(buyer.csrf().getHeaderName(), buyer.csrf().getToken())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode result = json.readTree(created.getResponse().getContentAsString());
        String path = result.get("sellerPath").asText();
        long id = result.get("id").asLong();
        Browser seller = anonymous();
        mvc.perform(post(path + "/start").session(seller.session())
                        .header(seller.csrf().getHeaderName(), seller.csrf().getToken())
                        .contentType("application/json").content("{\"sellerConsented\":true}"))
                .andExpect(status().isOk());
        when(checker.check(anyString(), any())).thenReturn(
                new TrueshotFirstPhotoChecker.Result(false, false, true),
                new TrueshotFirstPhotoChecker.Result(true, true, true));
        mvc.perform(multipart(path + "/photo").file(photo()).param("n", "1")
                        .session(seller.session()).header(seller.csrf().getHeaderName(), seller.csrf().getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.retryReasons[0]").exists())
                .andExpect(jsonPath("$.checks[0].code").value("PHOTO_QUALITY"))
                .andExpect(jsonPath("$.checks[0].status").value("PASSED"))
                .andExpect(jsonPath("$.checks[1].code").value("AUTH_CODE"))
                .andExpect(jsonPath("$.checks[1].status").value("FAILED"))
                .andExpect(jsonPath("$.failReasons[0]").exists());
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from trueshot_photos where request_id = ?", Integer.class, id));
        mvc.perform(multipart(path + "/photo").file(photo()).param("n", "1")
                        .session(seller.session()).header(seller.csrf().getHeaderName(), seller.csrf().getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.phase").value("FIRST_MASKING"))
                .andExpect(jsonPath("$.checks[0].status").value("PASSED"))
                .andExpect(jsonPath("$.checks[1].status").value("PASSED"))
                .andExpect(jsonPath("$.failReasons").isEmpty());
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from trueshot_photos where request_id = ?", Integer.class, id));
        verify(originals).upload(anyString(), any(byte[].class), anyString());
        mvc.perform(multipart(path + "/photo").file(photo()).param("n", "2")
                        .session(seller.session()).header(seller.csrf().getHeaderName(), seller.csrf().getToken()))
                .andExpect(status().isConflict());
        when(maskStorage.original(anyString())).thenReturn(photo().getBytes());
        when(maskStorage.masked(anyString())).thenReturn(photo().getBytes());
        when(maskDetector.detect(any())).thenReturn(new TrueshotMaskDetector.Detection(true,
                List.of(new Box(0.1,0.1,0.2,0.2))));
        mvc.perform(post(path+"/mask").session(seller.session())
                        .header(seller.csrf().getHeaderName(),seller.csrf().getToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maskStatus").value("READY"));
        mvc.perform(get(path+"/preview/1").session(buyer.session())).andExpect(status().isForbidden());
        mvc.perform(get(path+"/preview/1").session(seller.session()))
                .andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
        mvc.perform(get("/trueshot/{id}",id).session(buyer.session()))
                .andExpect(jsonPath("$.instruction2").isEmpty());
        var confirmation=mvc.perform(post(path+"/first-mask/confirm").session(seller.session())
                        .header(seller.csrf().getHeaderName(),seller.csrf().getToken())
                        .contentType("application/json")
                        .content("{\"boxes\":[{\"x\":0.5,\"y\":0.5,\"width\":0.1,\"height\":0.1}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.instruction2").isString())
                .andExpect(jsonPath("$.deadline2").exists())
                .andExpect(jsonPath("$.mission2.type").isString()).andReturn();
        var mission=json.readTree(confirmation.getResponse().getContentAsString()).get("mission2");
        assertEquals(mission.get("type").asText(),jdbc.queryForObject(
                "select mission_2_type from trueshot_requests where id=?",String.class,id));
        mvc.perform(get("/trueshot/{id}",id).session(buyer.session()))
                .andExpect(jsonPath("$.mission2.type").value(mission.get("type").asText()));
        when(screens.detect(any())).thenReturn(new BookingScreenDetector.Detection(true,true,.3,.3,.4,.4,"예매",true));
        String direction=mission.path("position").asText();
        int x=direction.equals("LEFT")?20:direction.equals("RIGHT")?380:200;
        int y=direction.equals("ABOVE")?20:direction.equals("BELOW")?380:200;
        String analysis="{\"imageWidth\":400,\"imageHeight\":400,\"hands\":[{\"x\":"+x+",\"y\":"+y
                +",\"gesture\":\"Victory\",\"confidence\":0.96}]}";
        var analysisPart=new MockMultipartFile("analysis","analysis.json","application/json",
                analysis.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart(path+"/photo").file(photo()).file(analysisPart).param("n","2")
                        .session(buyer.session()).header(buyer.csrf().getHeaderName(),buyer.csrf().getToken()))
                .andExpect(status().isForbidden());
        OffsetDateTime revealed=jdbc.queryForObject("select instruction_2_revealed_at from trueshot_requests where id=?",OffsetDateTime.class,id);
        OffsetDateTime deadline=jdbc.queryForObject("select deadline_2 from trueshot_requests where id=?",OffsetDateTime.class,id);
        assertEquals(60,java.time.Duration.between(revealed,deadline).getSeconds());
        mvc.perform(multipart(path + "/photo").file(photo()).file(analysisPart).param("n", "2")
                        .session(seller.session()).header(seller.csrf().getHeaderName(), seller.csrf().getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("SHARE_WAITING"));
        mvc.perform(get("/trueshot/{id}",id).session(buyer.session()))
                .andExpect(jsonPath("$.judgmentResult.missionCheck.status").value("MATCHED"))
                .andExpect(jsonPath("$.judgmentResult.missionCheck.mission.type").value(mission.get("type").asText()));
        mvc.perform(multipart(path + "/photo").file(photo()).file(analysisPart).param("n", "2")
                        .session(seller.session()).header(seller.csrf().getHeaderName(), seller.csrf().getToken()))
                .andExpect(status().isConflict());

        mvc.perform(get(path + "/result").session(seller.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canShare").value(true));
        // 두 번째 사진은 최종 판정 중 자동 가림된다. /mask 재호출은 단계 오류다.
        mvc.perform(post(path + "/mask").session(seller.session())
                        .header(seller.csrf().getHeaderName(), seller.csrf().getToken()))
                .andExpect(status().isConflict());
        mvc.perform(get(path + "/preview/2").session(seller.session()))
                .andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
        mvc.perform(get("/trueshot/{id}/photos/1", id).session(buyer.session()))
                .andExpect(status().isNotFound());
        mvc.perform(post(path + "/share").session(seller.session())
                        .header(seller.csrf().getHeaderName(), seller.csrf().getToken())
                        .contentType("application/json").content("{\"sharePhotos\":true,\"boxes\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("COMPLETED"));
        mvc.perform(get("/trueshot/{id}", id).session(buyer.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.photos.length()").value(2));
        for (int number : List.of(1, 2)) {
            mvc.perform(get("/trueshot/{id}/photos/{number}", id, number).session(buyer.session()))
                    .andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
        }
    }

    @Test
    void malformedMultipartRequestsAreClientErrors() throws Exception {
        Browser seller = anonymous();
        mvc.perform(multipart("/s/unknown/photo").param("n", "1")
                        .session(seller.session()).header(seller.csrf().getHeaderName(), seller.csrf().getToken()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/s/unknown/photo").file(photo())
                        .file(new MockMultipartFile("analysis", "analysis.json", "text/plain", "{}".getBytes()))
                        .param("n", "2").session(seller.session())
                        .header(seller.csrf().getHeaderName(), seller.csrf().getToken()))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void firstPhotoDeadlineIsEnforcedByServer() throws Exception {
        Browser buyer = buyer();
        var created = mvc.perform(post("/trueshot").session(buyer.session())
                        .header(buyer.csrf().getHeaderName(), buyer.csrf().getToken())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode result = json.readTree(created.getResponse().getContentAsString());
        String path = result.get("sellerPath").asText();
        long id = result.get("id").asLong();
        Browser seller = anonymous();
        mvc.perform(post(path + "/start").session(seller.session())
                        .header(seller.csrf().getHeaderName(), seller.csrf().getToken())
                        .contentType("application/json").content("{\"sellerConsented\":true}"))
                .andExpect(status().isOk());
        jdbc.update("update trueshot_requests set deadline_1 = ? where id = ?",
                OffsetDateTime.now().minusSeconds(1), id);
        mvc.perform(multipart(path + "/photo").file(photo()).param("n", "1")
                        .session(seller.session()).header(seller.csrf().getHeaderName(), seller.csrf().getToken()))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("FIRST_PHOTO_TIMEOUT"));
        assertEquals("FAILED", jdbc.queryForObject(
                "select status from trueshot_requests where id = ?", String.class, id));
        expiryJob.expireRequests();
        mvc.perform(multipart(path + "/photo").file(photo()).param("n", "1")
                        .session(seller.session()).header(seller.csrf().getHeaderName(), seller.csrf().getToken()))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("FIRST_PHOTO_TIMEOUT"));
        mvc.perform(get(path + "/result").session(seller.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.failureCode").value("FIRST_PHOTO_TIMEOUT"))
                .andExpect(jsonPath("$.phase").value("FAILED"))
                .andExpect(jsonPath("$.remainingMillis").isEmpty())
                .andExpect(jsonPath("$.activeDeadline").isEmpty());
        mvc.perform(get("/trueshot/{id}",id).session(buyer.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.failureCode").value("FIRST_PHOTO_TIMEOUT"));
    }

    @Test
    void incompleteMaskNeverExposesPreviewAndAllowsResultOnlyFlow() throws Exception {
        Browser buyer=buyer();
        var created=mvc.perform(post("/trueshot").session(buyer.session())
                .header(buyer.csrf().getHeaderName(),buyer.csrf().getToken())
                .contentType("application/json").content("{}"))
                .andExpect(status().isCreated()).andReturn();
        var data=json.readTree(created.getResponse().getContentAsString());
        String path=data.get("sellerPath").asText();
        long id=data.get("id").asLong();
        Browser seller=anonymous();
        mvc.perform(post(path+"/start").session(seller.session())
                .header(seller.csrf().getHeaderName(),seller.csrf().getToken())
                .contentType("application/json").content("{\"sellerConsented\":true}"))
                .andExpect(status().isOk());
        when(checker.check(anyString(),any())).thenReturn(new TrueshotFirstPhotoChecker.Result(true,true,true));
        mvc.perform(multipart(path+"/photo").file(photo()).param("n","1").session(seller.session())
                .header(seller.csrf().getHeaderName(),seller.csrf().getToken())).andExpect(status().isOk());
        when(maskStorage.original(anyString())).thenReturn(photo().getBytes());
        when(maskDetector.detect(any())).thenReturn(new TrueshotMaskDetector.Detection(false,List.of()));
        mvc.perform(post(path+"/mask").session(seller.session())
                .header(seller.csrf().getHeaderName(),seller.csrf().getToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maskStatus").value("FAILED"))
                .andExpect(jsonPath("$.previewPath").isEmpty());
        mvc.perform(get(path+"/preview/1").session(seller.session())).andExpect(status().isNotFound());
        mvc.perform(post(path+"/first-mask/confirm").session(seller.session())
                .header(seller.csrf().getHeaderName(),seller.csrf().getToken())
                .contentType("application/json").content("{\"boxes\":[]}"))
                .andExpect(status().isOk());
        assertEquals(0,jdbc.queryForObject("select count(*) from trueshot_photos where request_id=? and masked_image is not null",Integer.class,id));
    }

    private MockMultipartFile photo() throws Exception {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return new MockMultipartFile("photo", "ticket.jpg", "image/jpeg", output.toByteArray());
    }

    @Test void sellerRefreshRestoresCodeAndDeadlineOnlyForStartedBrowser() throws Exception {
        var buyer=buyer();
        var created=mvc.perform(post("/trueshot").session(buyer.session())
                .header(buyer.csrf().getHeaderName(),buyer.csrf().getToken())
                .contentType("application/json").content("{\"title\":\"복구할 인증\"}"))
                .andExpect(status().isCreated()).andReturn();
        var data=json.readTree(created.getResponse().getContentAsString());
        String path=data.get("sellerPath").asText();
        var seller=anonymous();
        var started=mvc.perform(post(path+"/start").session(seller.session())
                .header(seller.csrf().getHeaderName(),seller.csrf().getToken())
                .contentType("application/json").content("{\"sellerConsented\":true}"))
                .andExpect(status().isOk()).andReturn();
        var start=json.readTree(started.getResponse().getContentAsString());
        for (int i=0;i<2;i++) {
            var refreshed=mvc.perform(get(path+"/result").session(seller.session()))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                    .andExpect(jsonPath("$.title").value("복구할 인증"))
                    .andExpect(jsonPath("$.code").value(start.get("code").asText()))
                    .andExpect(jsonPath("$.phase").value("FIRST_CHECKING"))
                    .andExpect(jsonPath("$.mission2").isEmpty())
                    .andExpect(jsonPath("$.deadline2").isEmpty())
                    .andExpect(jsonPath("$.serverTime").isString())
                    .andExpect(jsonPath("$.photos").isEmpty()).andReturn();
            var restored=json.readTree(refreshed.getResponse().getContentAsString());
            assertTrue(OffsetDateTime.parse(start.get("deadline1").asText())
                    .isEqual(OffsetDateTime.parse(restored.get("deadline1").asText())));
            assertTrue(OffsetDateTime.parse(start.get("expiresAt").asText())
                    .isEqual(OffsetDateTime.parse(restored.get("expiresAt").asText())));
        }
        mvc.perform(get(path+"/result")).andExpect(status().isForbidden());
        mvc.perform(get(path+"/result").session(buyer.session())).andExpect(status().isForbidden());
        mvc.perform(get(path+"/result").session(anonymous().session())).andExpect(status().isForbidden());
        mvc.perform(get(path)).andExpect(status().isConflict());
        mvc.perform(post(path+"/start").session(seller.session())
                .header(seller.csrf().getHeaderName(),seller.csrf().getToken())
                .contentType("application/json").content("{\"sellerConsented\":true}"))
                .andExpect(status().isConflict());
        assertEquals(0,jdbc.queryForObject("select count(*) from trueshot_photos where request_id=?",Integer.class,data.get("id").asLong()));
    }

    @Test void sellerRefreshRestoresMaskingAndSecondMissionWithoutSideEffects() throws Exception {
        var buyer=buyer();
        var created=mvc.perform(post("/trueshot").session(buyer.session())
                .header(buyer.csrf().getHeaderName(),buyer.csrf().getToken())
                .contentType("application/json").content("{}"))
                .andExpect(status().isCreated()).andReturn();
        var data=json.readTree(created.getResponse().getContentAsString());
        String path=data.get("sellerPath").asText();
        var seller=anonymous();
        mvc.perform(post(path+"/start").session(seller.session())
                .header(seller.csrf().getHeaderName(),seller.csrf().getToken())
                .contentType("application/json").content("{\"sellerConsented\":true}"))
                .andExpect(status().isOk());
        when(checker.check(anyString(),any())).thenReturn(new TrueshotFirstPhotoChecker.Result(true,true,true));
        mvc.perform(multipart(path+"/photo").file(photo()).param("n","1").session(seller.session())
                .header(seller.csrf().getHeaderName(),seller.csrf().getToken())).andExpect(status().isOk());
        mvc.perform(get(path+"/result").session(seller.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("FIRST_MASKING"))
                .andExpect(jsonPath("$.photos[0].number").value(1))
                .andExpect(jsonPath("$.photos[0].maskStatus").value("PENDING"))
                .andExpect(jsonPath("$.photos[0].previewPath").isEmpty());
        org.mockito.Mockito.verifyNoInteractions(maskDetector,maskStorage);
        when(maskStorage.original(anyString())).thenReturn(photo().getBytes());
        when(maskStorage.masked(anyString())).thenReturn(photo().getBytes());
        when(maskDetector.detect(any())).thenReturn(new TrueshotMaskDetector.Detection(true,List.of(new Box(.1,.1,.1,.1))));
        mvc.perform(post(path+"/mask").session(seller.session())
                .header(seller.csrf().getHeaderName(),seller.csrf().getToken())).andExpect(status().isOk());
        var masking=mvc.perform(get(path+"/result").session(seller.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.photos[0].maskStatus").value("READY"))
                .andExpect(jsonPath("$.photos[0].previewPath").value(path+"/preview/1"))
                .andExpect(jsonPath("$.photos[0].originalStorageKey").doesNotExist())
                .andExpect(jsonPath("$.photos[0].maskedImage").doesNotExist()).andReturn();
        assertTrue(!masking.getResponse().getContentAsString().contains("trueshots/"));
        mvc.perform(get(path+"/preview/1").session(seller.session())).andExpect(status().isOk());
        var confirmed=mvc.perform(post(path+"/first-mask/confirm").session(seller.session())
                .header(seller.csrf().getHeaderName(),seller.csrf().getToken())
                .contentType("application/json").content("{\"boxes\":[]}"))
                .andExpect(status().isOk()).andReturn();
        var instruction=json.readTree(confirmed.getResponse().getContentAsString());
        for (int i=0;i<2;i++) {
            var refreshed=mvc.perform(get(path+"/result").session(seller.session())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.phase").value("SECOND_WAITING"))
                    .andExpect(jsonPath("$.instruction2").value(instruction.get("instruction2").asText()))
                    .andExpect(jsonPath("$.photos[0].previewPath").isEmpty()).andReturn();
            var restored=json.readTree(refreshed.getResponse().getContentAsString());
            assertEquals(instruction.get("mission2"),restored.get("mission2"));
            assertTrue(OffsetDateTime.parse(instruction.get("deadline2").asText())
                    .isEqual(OffsetDateTime.parse(restored.get("deadline2").asText())));
        }
        mvc.perform(get(path+"/preview/1").session(seller.session())).andExpect(status().isConflict());
        verify(maskDetector,org.mockito.Mockito.times(1)).detect(any());
    }

    @Test
    void firstPhotoReceivedInTimeRemainsAcceptedAfterAiOrStorageDelay() throws Exception {
        for (boolean expireDuringAnalysis : List.of(true,false)) {
            var f=startedRequest();
            when(checker.check(anyString(),any())).thenAnswer(call -> {
                if (expireDuringAnalysis) expireDuringProcessing(f.id());
                return new TrueshotFirstPhotoChecker.Result(true,true,true);
            });
            doAnswer(call -> {
                if (!expireDuringAnalysis) expireDuringProcessing(f.id());
                return null;
            }).when(originals).upload(anyString(),any(),anyString());
            mvc.perform(multipart(f.path()+"/photo").file(photo()).param("n","1")
                            .session(f.seller().session())
                            .header(f.seller().csrf().getHeaderName(),f.seller().csrf().getToken()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true))
                    .andExpect(jsonPath("$.phase").value("FIRST_MASKING"));
            var accepted=requests.findById(f.id()).orElseThrow();
            assertEquals("FIRST_MASKING",accepted.getPhase().name());
            assertNotNull(accepted.getFirstVerifiedAt());
            assertNotNull(photos.findByRequestIdAndNumber(f.id(),(short)1).orElseThrow().getOriginalStorageKey());
        }
    }

    private void expireDuringProcessing(long id) {
        // 접수 검사가 끝난 뒤 AI 또는 저장소 처리 중 기한이 지난 상황을 재현한다.
        var request=requests.findById(id).orElseThrow();
        ReflectionTestUtils.setField(request,"expiresAt",OffsetDateTime.now().minusSeconds(1));
    }

    @Test
    void firstPhotoReceivedInTimeCanFinishStorageAfterPhotoDeadline() throws Exception {
        var f=startedRequest();
        when(checker.check(anyString(),any())).thenReturn(new TrueshotFirstPhotoChecker.Result(true,true,true));
        doAnswer(call -> {
            var request=requests.findById(f.id()).orElseThrow();
            ReflectionTestUtils.setField(request,"deadline1",OffsetDateTime.now().minusSeconds(1));
            return null;
        }).when(originals).upload(anyString(),any(),anyString());
        mvc.perform(multipart(f.path()+"/photo").file(photo()).param("n","1")
                        .session(f.seller().session())
                        .header(f.seller().csrf().getHeaderName(),f.seller().csrf().getToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.phase").value("FIRST_MASKING"));
        assertNotNull(requests.findById(f.id()).orElseThrow().getFirstVerifiedAt());
    }

    private UploadFixture startedRequest() throws Exception {
        var buyer=buyer();
        var response=mvc.perform(post("/trueshot").session(buyer.session())
                        .header(buyer.csrf().getHeaderName(),buyer.csrf().getToken())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var created=json.readTree(response.getContentAsString());
        var seller=anonymous();
        String path=created.path("sellerPath").asText();
        mvc.perform(post(path+"/start").session(seller.session())
                        .header(seller.csrf().getHeaderName(),seller.csrf().getToken())
                        .contentType("application/json").content("{\"sellerConsented\":true}"))
                .andExpect(status().isOk());
        return new UploadFixture(created.path("id").asLong(),path,buyer,seller);
    }

    private record UploadFixture(long id,String path,Browser buyer,Browser seller) {}

    private Browser buyer() throws Exception {
        String unique = UUID.randomUUID().toString();
        User user = users.save(User.create(unique + "@example.com", "unused", unique,
                "unused", "1234", OffsetDateTime.now()));
        MockHttpSession session = new MockHttpSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user.getId().toString(), null, List.of()));
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        return browser(session);
    }

    private Browser anonymous() throws Exception {
        return browser(new MockHttpSession());
    }

    private Browser browser(MockHttpSession session) throws Exception {
        var response = mvc.perform(get("/auth/csrf").session(session)).andReturn();
        return new Browser(session,
                (CsrfToken) response.getRequest().getAttribute(CsrfToken.class.getName()));
    }

    private record Browser(MockHttpSession session, CsrfToken csrf) {}
}
