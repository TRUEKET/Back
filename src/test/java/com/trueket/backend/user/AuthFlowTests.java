package com.trueket.backend.user;

import com.trueket.backend.user.dto.AuthDtos.*;
import com.trueket.backend.user.service.AuthService;
import com.trueket.backend.global.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "auth.phone.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "auth.phone.hash-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
    "server.servlet.session.cookie.secure=false"
})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class AuthFlowTests {
    @Autowired AuthService auth;
    @Autowired com.trueket.backend.user.service.UserService userService;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.trueket.backend.user.repository.UserRepository users;
    @Autowired com.trueket.backend.user.repository.PhoneVerificationRepository verifications;
    @Autowired com.trueket.backend.user.service.PhonePrivacy privacy;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;

    @Test void reissueReplacesRandomCodeAndResetsFailureCount() {
        var first = auth.send(new PhoneSend("01081812222"));
        assertTrue(first.demoSms().code().matches("[0-9]{6}"));
        assertEquals("010-****-2222", first.demoSms().maskedPhone());
        String stored = jdbc.queryForObject("select code_hash from phone_verifications where id = ?", String.class, first.verificationId());
        assertNotEquals(first.demoSms().code(), stored);
        jdbc.update("update phone_verifications set created_at = ?, attempt_count = 4 where id = ?",
            java.time.OffsetDateTime.now().minusSeconds(61), first.verificationId());
        var second = auth.send(new PhoneSend("01081812222"));
        assertNotEquals(first.demoSms().code(), second.demoSms().code());
        assertEquals(0, jdbc.queryForObject("select attempt_count from phone_verifications where id = ?", Integer.class, second.verificationId()));
        assertThrows(ApiException.class, () -> auth.verify(new PhoneVerify(first.verificationId(), "01081812222", first.demoSms().code())));
        auth.verify(new PhoneVerify(second.verificationId(), "01081812222", second.demoSms().code()));
    }

    @Test void nonDemoSenderReceivesRandomCodeButResponseDoesNotRevealIt() {
        var delivered = new java.util.concurrent.atomic.AtomicReference<String>();
        var production = new AuthService(users, verifications, privacy, passwords,
            (phone, code) -> delivered.set(code), new org.springframework.mock.env.MockEnvironment());
        var sent = new org.springframework.transaction.support.TransactionTemplate(transactions)
            .execute(status -> production.send(new PhoneSend("01081813333")));
        assertNull(sent.demoSms());
        assertTrue(delivered.get().matches("[0-9]{6}"));
        auth.verify(new PhoneVerify(sent.verificationId(), "01081813333", delivered.get()));
    }

    @Test void demoPageIsAvailableAndExplainsSimulatedDelivery() throws Exception {
        mvc.perform(get("/auth/demo")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("실제 문자는 발송되지 않습니다")));
    }

    @Test void nonDemoResponseOmitsInboxAndPageIsNotRegistered() throws Exception {
        var mockAuth = org.mockito.Mockito.mock(AuthService.class);
        org.mockito.Mockito.when(mockAuth.send(org.mockito.ArgumentMatchers.any()))
            .thenReturn(new PhoneSent(1L, java.time.OffsetDateTime.now().plusMinutes(3), null));
        var standalone = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(new com.trueket.backend.user.controller.AuthController(mockAuth, users)).build();
        standalone.perform(post("/auth/phone/send").contentType("application/json").content("{\"phone\":\"01081815555\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.demoSms").doesNotExist())
            .andExpect(jsonPath("$.code").doesNotExist());
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=production")
            .withUserConfiguration(com.trueket.backend.user.controller.DemoAuthController.class)
            .run(context -> assertTrue(context.getBeansOfType(com.trueket.backend.user.controller.DemoAuthController.class).isEmpty()));
    }

    @Test void signupRequiresVerifiedPhoneAndConsumesVerification() {
        var sent=auth.send(new PhoneSend("010-1234-5678"));
        var signup=new Signup("fan@example.com", "password123", "01012345678", sent.verificationId(), true);
        assertThrows(ApiException.class, () -> auth.signup(signup));
        assertThrows(ApiException.class, () -> auth.verify(new PhoneVerify(sent.verificationId(), "01012345678", (sent.demoSms().code().equals("000000") ? "000001" : "000000"))));
        auth.verify(new PhoneVerify(sent.verificationId(), "01012345678", sent.demoSms().code()));
        var user=auth.signup(signup);
        assertEquals("5678", user.getPhoneLast4());
        assertNotEquals("password123", user.getPasswordHash());
        assertNotNull(user.getPrivacyAgreedAt());
        assertEquals(user.getId(), auth.login(new Login("fan@example.com", "password123")).getId());
        assertThrows(ApiException.class, () -> auth.login(new Login("fan@example.com", "wrong")));
        assertThrows(ApiException.class, () -> auth.signup(new Signup("another@example.com", "password123", "01012345678", sent.verificationId(), true)));
    }

    @Test void passwordChangeChecksCurrentPasswordConfirmationAndUpdatesLogin() {
        var sent = auth.send(new PhoneSend("01033332222"));
        auth.verify(new PhoneVerify(sent.verificationId(), "01033332222", sent.demoSms().code()));
        var user = auth.signup(new Signup("password-change@example.com", "original123", "01033332222", sent.verificationId(), true));
        assertThrows(ApiException.class, () -> userService.changePassword(user.getId(),
            new com.trueket.backend.user.dto.PasswordChangeRequest("wrong", "changed123", "changed123")));
        assertThrows(ApiException.class, () -> userService.changePassword(user.getId(),
            new com.trueket.backend.user.dto.PasswordChangeRequest("original123", "changed123", "different123")));
        assertThrows(ApiException.class, () -> userService.changePassword(user.getId(),
            new com.trueket.backend.user.dto.PasswordChangeRequest("original123", "original123", "original123")));
        userService.changePassword(user.getId(),
            new com.trueket.backend.user.dto.PasswordChangeRequest("original123", "changed123", "changed123"));
        assertThrows(ApiException.class, () -> auth.login(new Login("password-change@example.com", "original123")));
        assertEquals(user.getId(), auth.login(new Login("password-change@example.com", "changed123")).getId());
    }

    @Test void locksAfterFiveFailuresAndLimitsResending() {
        var sent=auth.send(new PhoneSend("01099998888"));
        assertThrows(ApiException.class, () -> auth.send(new PhoneSend("01099998888")));
        for (int i=0;i<5;i++) assertThrows(ApiException.class,
            () -> auth.verify(new PhoneVerify(sent.verificationId(), "01099998888", (sent.demoSms().code().equals("000000") ? "000001" : "000000"))));
        assertThrows(ApiException.class, () -> auth.verify(new PhoneVerify(sent.verificationId(), "01099998888", sent.demoSms().code())));
    }

    @Test void verificationCannotBeClaimedFromAnotherSession() throws Exception {
        var sent = auth.send(new PhoneSend("01022221111"));
        auth.verify(new PhoneVerify(sent.verificationId(), "01022221111", sent.demoSms().code()));
        var initial = mvc.perform(get("/auth/csrf")).andReturn();
        var session = (MockHttpSession) initial.getRequest().getSession();
        var csrf = (CsrfToken) initial.getRequest().getAttribute(CsrfToken.class.getName());
        mvc.perform(post("/auth/signup").session(session).header(csrf.getHeaderName(), csrf.getToken())
            .contentType("application/json").content("""
                {"email":"other-browser@example.com","password":"password123","phone":"01022221111",
                 "verificationId":%s,"privacyConsent":true}
                """.formatted(sent.verificationId())))
            .andExpect(status().isBadRequest());
    }

    @Test void expiredCodesAndConsentAreRejected() throws Exception {
        var sent=auth.send(new PhoneSend("01055554444"));
        jdbc.update("update phone_verifications set expires_at = ? where id = ?",
            java.time.OffsetDateTime.now().minusMinutes(1), sent.verificationId());
        assertThrows(ApiException.class, () -> auth.verify(new PhoneVerify(sent.verificationId(), "01055554444", sent.demoSms().code())));
        var result=mvc.perform(get("/auth/csrf")).andReturn();
        var token=(CsrfToken) result.getRequest().getAttribute(CsrfToken.class.getName());
        mvc.perform(post("/auth/signup").session((MockHttpSession) result.getRequest().getSession())
            .header(token.getHeaderName(), token.getToken()).contentType("application/json")
            .content("""
                {"email":"consent@example.com","password":"password123","phone":"01055554444",
                 "verificationId":"%s","privacyConsent":false}
                """.formatted(sent.verificationId())))
            .andExpect(status().isBadRequest());
    }

    @Test void signupCreatesAuthenticatedSessionAndLogoutInvalidatesIt() throws Exception {
        var initial=mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession) initial.getRequest().getSession();
        var csrf=(CsrfToken) initial.getRequest().getAttribute(CsrfToken.class.getName());
        var sendResult = mvc.perform(post("/auth/phone/send").session(session)
            .header(csrf.getHeaderName(), csrf.getToken()).contentType("application/json")
            .content("{\"phone\":\"01077776666\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var sentJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(sendResult.getResponse().getContentAsString());
        long verificationId = sentJson.get("verificationId").asLong();
        String code = sentJson.path("demoSms").path("code").asText();
        mvc.perform(post("/auth/phone/verify").session(session)
            .header(csrf.getHeaderName(), csrf.getToken()).contentType("application/json")
            .content("""
                {"phone":"01077776666","verificationId":%s,"code":"%s"}
                """.formatted(verificationId, code)))
            .andExpect(status().isOk());
        mvc.perform(post("/auth/signup").session(session).header(csrf.getHeaderName(), csrf.getToken())
            .contentType("application/json").content("""
                {"email":"session@example.com","password":"password123","phone":"01077776666",
                 "verificationId":"%s","privacyConsent":true}
                """.formatted(verificationId)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.phoneLast4").value("6666"));
        mvc.perform(get("/me").session(session)).andExpect(status().isOk());
        var refreshed=mvc.perform(get("/auth/csrf").session(session)).andReturn();
        var logoutToken=(CsrfToken) refreshed.getRequest().getAttribute(CsrfToken.class.getName());
        mvc.perform(post("/auth/logout").session(session).header(logoutToken.getHeaderName(), logoutToken.getToken()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
    }
}
