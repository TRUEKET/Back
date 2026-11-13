package com.trueket.backend.trueshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.trueshot.service.TrueshotExpiryJob;
import com.trueket.backend.trueshot.service.TrueshotFirstPhotoChecker;
import com.trueket.backend.trueshot.service.TrueshotOriginalStore;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.user.repository.UserRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TrueshotRequestApiTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired TrueshotExpiryJob expiryJob;
    @MockitoBean TrueshotFirstPhotoChecker checker;
    @MockitoBean TrueshotOriginalStore originals;
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
                .andExpect(jsonPath("$.retryReasons[0]").exists());
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from trueshot_photos where request_id = ?", Integer.class, id));
        mvc.perform(multipart(path + "/photo").file(photo()).param("n", "1")
                        .session(seller.session()).header(seller.csrf().getHeaderName(), seller.csrf().getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.phase").value("FIRST_MASKING"));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from trueshot_photos where request_id = ?", Integer.class, id));
        verify(originals).upload(anyString(), any(byte[].class), anyString());
        mvc.perform(multipart(path + "/photo").file(photo()).param("n", "2")
                        .session(seller.session()).header(seller.csrf().getHeaderName(), seller.csrf().getToken()))
                .andExpect(status().isConflict());
        // 개인정보 가림 단계 완료는 다음 작업이므로 테스트에서 그 상태만 준비한다.
        jdbc.update("update trueshot_requests set phase = 'SECOND_WAITING', deadline_2 = ? where id = ?",
                OffsetDateTime.now().plusMinutes(1), id);
        mvc.perform(multipart(path + "/photo").file(photo()).param("n", "2")
                        .session(seller.session()).header(seller.csrf().getHeaderName(), seller.csrf().getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("JUDGING"));
        mvc.perform(multipart(path + "/photo").file(photo()).param("n", "2")
                        .session(seller.session()).header(seller.csrf().getHeaderName(), seller.csrf().getToken()))
                .andExpect(status().isConflict());
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
                .andExpect(status().isGone());
        assertEquals("FAILED", jdbc.queryForObject(
                "select status from trueshot_requests where id = ?", String.class, id));
    }

    private MockMultipartFile photo() throws Exception {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return new MockMultipartFile("photo", "ticket.jpg", "image/jpeg", output.toByteArray());
    }

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
