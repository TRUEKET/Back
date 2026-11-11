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

    @Test void signupRequiresVerifiedPhoneAndConsumesVerification() {
        var sent=auth.send(new PhoneSend("010-1234-5678"));
        var signup=new Signup("fan@example.com", "password123", "01012345678", sent.verificationId(), true);
        assertThrows(ApiException.class, () -> auth.signup(signup));
        assertThrows(ApiException.class, () -> auth.verify(new PhoneVerify(sent.verificationId(), "01012345678", "000000")));
        auth.verify(new PhoneVerify(sent.verificationId(), "01012345678", "123456"));
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
        auth.verify(new PhoneVerify(sent.verificationId(), "01033332222", "123456"));
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
            () -> auth.verify(new PhoneVerify(sent.verificationId(), "01099998888", "000000")));
        assertThrows(ApiException.class, () -> auth.verify(new PhoneVerify(sent.verificationId(), "01099998888", "123456")));
    }

    @Test void verificationCannotBeClaimedFromAnotherSession() throws Exception {
        var sent = auth.send(new PhoneSend("01022221111"));
        auth.verify(new PhoneVerify(sent.verificationId(), "01022221111", "123456"));
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
        assertThrows(ApiException.class, () -> auth.verify(new PhoneVerify(sent.verificationId(), "01055554444", "123456")));
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
            .andExpect(status().isOk()).andReturn();
        long verificationId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(sendResult.getResponse().getContentAsString()).get("verificationId").asLong();
        mvc.perform(post("/auth/phone/verify").session(session)
            .header(csrf.getHeaderName(), csrf.getToken()).contentType("application/json")
            .content("""
                {"phone":"01077776666","verificationId":%s,"code":"123456"}
                """.formatted(verificationId)))
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
