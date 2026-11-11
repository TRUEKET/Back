package com.trueket.backend.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.user.dto.AuthDtos.*;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.user.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AccountFlowTests {
    private static final AtomicInteger NUMBERS = new AtomicInteger(10000);
    private static final String PASSWORD = "password123";
    private final ObjectMapper json = new ObjectMapper();
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;

    @Test
    void fullPhoneIsOnlyReturnedForAuthenticatedAccountEditing() throws Exception {
        String phone = nextPhone();
        User user = signup(phone);
        Browser browser = login(user);
        mvc.perform(get("/me").session(browser.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneLast4").value(phone.substring(7)))
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(get("/me/account").session(browser.session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.phone").value(phone))
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.phoneEnc").doesNotExist());
        mvc.perform(get("/me/account")).andExpect(status().isUnauthorized());
    }

    @Test
    void verifiedPhoneIsSavedSeparatelyAndCannotBeReused() throws Exception {
        String original = nextPhone();
        User user = signup(original);
        Browser browser = login(user);
        String replacement = nextPhone();
        long verificationId = send(browser, replacement);
        verify(browser, replacement, verificationId, "123456").andExpect(status().isOk());
        mvc.perform(get("/me/account").session(browser.session()))
                .andExpect(jsonPath("$.phone").value(original));
        save(browser, replacement, verificationId).andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneLast4").value(replacement.substring(7)));
        mvc.perform(get("/me/account").session(browser.session()))
                .andExpect(jsonPath("$.phone").value(replacement));
        mvc.perform(get("/me").session(browser.session()))
                .andExpect(jsonPath("$.phoneLast4").value(replacement.substring(7)));
        save(browser, replacement, verificationId).andExpect(status().isBadRequest());
        assertNotNull(jdbc.queryForObject("select consumed_at from phone_verifications where id = ?",
                OffsetDateTime.class, verificationId));
        assertEquals(user.getId(), auth.login(new Login(user.getEmail(), PASSWORD)).getId());
    }

    @Test
    void duplicatePhoneAndUnverifiedOrDifferentPhoneAreRejected() throws Exception {
        User user = signup(nextPhone());
        String occupied = nextPhone();
        signup(occupied);
        Browser browser = login(user);
        postJson(browser, "/me/phone/send", Map.of("phone", occupied))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("다른 계정에 등록된 번호예요"));
        String replacement = nextPhone();
        long verificationId = send(browser, replacement);
        save(browser, replacement, verificationId).andExpect(status().isBadRequest());
        verify(browser, replacement, verificationId, "123456").andExpect(status().isOk());
        save(browser, nextPhone(), verificationId).andExpect(status().isBadRequest());
        save(browser, replacement, verificationId).andExpect(status().isOk());
    }

    @Test
    void verificationIsBoundToBothUserAndBrowser() throws Exception {
        User user = signup(nextPhone());
        Browser first = login(user);
        Browser second = login(user);
        Browser otherUser = login(signup(nextPhone()));
        String replacement = nextPhone();
        long verificationId = send(first, replacement);
        verify(second, replacement, verificationId, "123456").andExpect(status().isBadRequest());
        verify(otherUser, replacement, verificationId, "123456").andExpect(status().isBadRequest());
        verify(first, replacement, verificationId, "123456").andExpect(status().isOk());
        save(second, replacement, verificationId).andExpect(status().isBadRequest());
        save(otherUser, replacement, verificationId).andExpect(status().isBadRequest());
        save(first, replacement, verificationId).andExpect(status().isOk());
    }

    @Test
    void retryLimitExpiryAndCsrfAreEnforced() throws Exception {
        Browser browser = login(signup(nextPhone()));
        String replacement = nextPhone();
        long verificationId = send(browser, replacement);
        postJson(browser, "/me/phone/send", Map.of("phone", replacement))
                .andExpect(status().isTooManyRequests());
        for (int attempt = 0; attempt < 5; attempt++) {
            verify(browser, replacement, verificationId, "000000").andExpect(status().isBadRequest());
        }
        verify(browser, replacement, verificationId, "123456").andExpect(status().isBadRequest());
        assertEquals(5, jdbc.queryForObject("select attempt_count from phone_verifications where id = ?",
                Integer.class, verificationId));
        String expired = nextPhone();
        long expiredId = send(browser, expired);
        jdbc.update("update phone_verifications set expires_at = ? where id = ?",
                OffsetDateTime.now().minusSeconds(1), expiredId);
        verify(browser, expired, expiredId, "123456").andExpect(status().isBadRequest());
        mvc.perform(post("/me/phone/send").session(browser.session()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("phone", nextPhone()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void resendingFromAnotherBrowserInvalidatesPreviouslyVerifiedProof() throws Exception {
        User user = signup(nextPhone());
        Browser first = login(user);
        Browser second = login(user);
        String replacement = nextPhone();
        long verificationId = send(first, replacement);
        verify(first, replacement, verificationId, "123456").andExpect(status().isOk());
        jdbc.update("update phone_verifications set created_at = ? where id = ?",
                OffsetDateTime.now().minusMinutes(2), verificationId);
        long reissuedId = send(second, replacement);
        verify(second, replacement, reissuedId, "123456").andExpect(status().isOk());
        save(first, replacement, verificationId).andExpect(status().isBadRequest());
        save(second, replacement, reissuedId).andExpect(status().isOk());
    }

    @Test
    void numberClaimedBeforeSaveIsRejectedWithoutConsumingProof() throws Exception {
        Browser browser = login(signup(nextPhone()));
        String replacement = nextPhone();
        long verificationId = send(browser, replacement);
        verify(browser, replacement, verificationId, "123456").andExpect(status().isOk());
        signup(replacement);
        save(browser, replacement, verificationId).andExpect(status().isBadRequest());
        assertNull(jdbc.queryForObject("select consumed_at from phone_verifications where id = ?",
                OffsetDateTime.class, verificationId));
    }

    @Test
    void signupVerificationCannotBeUsedForPhoneChange() throws Exception {
        User user = signup(nextPhone());
        Browser browser = login(user);
        String replacement = nextPhone();
        PhoneSent sent = auth.send(new PhoneSend(replacement));
        auth.verify(new PhoneVerify(sent.verificationId(), replacement, "123456"));
        save(browser, replacement, sent.verificationId()).andExpect(status().isBadRequest());
        assertThrows(com.trueket.backend.global.exception.ApiException.class,
                () -> auth.consumePhoneChange(user.getId(),
                        new com.trueket.backend.user.dto.PhoneChangeRequest(replacement, sent.verificationId()), sent.expiresAt()));
    }

    private User signup(String phone) {
        PhoneSent sent = auth.send(new PhoneSend(phone));
        auth.verify(new PhoneVerify(sent.verificationId(), phone, "123456"));
        return auth.signup(new Signup("account-" + phone + "@example.com", PASSWORD, phone,
                sent.verificationId(), true));
    }

    private Browser login(User user) throws Exception {
        var initial = mvc.perform(get("/auth/csrf")).andReturn();
        MockHttpSession session = (MockHttpSession) initial.getRequest().getSession();
        CsrfToken token = (CsrfToken) initial.getRequest().getAttribute(CsrfToken.class.getName());
        postJson(new Browser(session, token), "/auth/login", Map.of("email", user.getEmail(), "password", PASSWORD))
                .andExpect(status().isOk());
        var refreshed = mvc.perform(get("/auth/csrf").session(session)).andReturn();
        return new Browser(session, (CsrfToken) refreshed.getRequest().getAttribute(CsrfToken.class.getName()));
    }

    private long send(Browser browser, String phone) throws Exception {
        var result = postJson(browser, "/me/phone/send", Map.of("phone", phone))
                .andExpect(status().isOk()).andReturn();
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        return response.get("verificationId").asLong();
    }

    private ResultActions verify(Browser browser, String phone, long id, String code) throws Exception {
        return postJson(browser, "/me/phone/verify", Map.of("phone", phone, "verificationId", id, "code", code));
    }

    private ResultActions save(Browser browser, String phone, long id) throws Exception {
        return mvc.perform(patch("/me/phone").session(browser.session())
                .header(browser.csrf().getHeaderName(), browser.csrf().getToken())
                .contentType("application/json").content(json.writeValueAsString(Map.of("phone", phone, "verificationId", id))));
    }

    private ResultActions postJson(Browser browser, String path, Object body) throws Exception {
        return mvc.perform(post(path).session(browser.session())
                .header(browser.csrf().getHeaderName(), browser.csrf().getToken())
                .contentType("application/json").content(json.writeValueAsString(body)));
    }

    private String nextPhone() {
        return "010" + String.format("%08d", NUMBERS.incrementAndGet());
    }

    private record Browser(MockHttpSession session, CsrfToken csrf) {}
}
