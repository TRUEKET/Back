package com.trueket.backend.trueshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.trueshot.service.TrueshotExpiryJob;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TrueshotRequestApiTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired TrueshotExpiryJob expiryJob;
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
