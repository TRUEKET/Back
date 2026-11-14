package com.trueket.backend.analyze;

import com.trueket.backend.analyze.dto.*;
import com.trueket.backend.analyze.service.*;
import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:analysislogin;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AnalysisLoginFlowTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired AnalysisSessionStore store;
    @Autowired AnalysisReportSourceService source;

    User user() {
        return users.saveAndFlush(User.create(UUID.randomUUID()+"@test.invalid",passwords.encode("password123"),
                UUID.randomUUID().toString(),"encrypted","1234",OffsetDateTime.now()));
    }
    MockHttpSession browser() throws Exception {
        return (MockHttpSession)mvc.perform(get("/auth/csrf")).andReturn().getRequest().getSession();
    }
    CsrfToken csrf(MockHttpSession session) throws Exception {
        return (CsrfToken)mvc.perform(get("/auth/csrf").session(session)).andReturn()
                .getRequest().getAttribute(CsrfToken.class.getName());
    }
    UUID analyze(MockHttpSession session) {
        UUID owner=UUID.randomUUID();
        session.setAttribute("analysisOwner",owner);
        return store.save(owner,new AnalysisSessionStore.Snapshot(new ModelExtraction(true,"transfer",false,
                List.of(),List.of(),new ModelExtraction.Extracted(List.of(),List.of(),List.of(),List.of()),
                null,List.of()),List.of()));
    }
    void login(MockHttpSession session,User user) throws Exception {
        var token=csrf(session);
        mvc.perform(post("/auth/login").session(session).header(token.getHeaderName(),token.getToken())
                .contentType("application/json")
                .content("{\"email\":\""+user.getEmail()+"\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());
    }
    @Test void anonymousAnalysisSurvivesLoginAndSessionIdRotation() throws Exception {
        var session=browser();
        UUID id=analyze(session);
        String previous=session.getId();
        var user=user();
        login(session,user);
        assertNotEquals(previous,session.getId());
        assertEquals(user.getId().longValue(),source.load(id,session).userId());
        store.delete(id,(UUID)session.getAttribute("analysisOwner"));
    }
    @Test void sameAccountLoginKeepsResultButDifferentAccountRemovesIt() throws Exception {
        var session=browser();var first=user();login(session,first);
        UUID id=analyze(session);
        UUID owner=(UUID)session.getAttribute("analysisOwner");
        login(session,first);
        assertEquals(id,source.load(id,session).analysisId());
        login(session,user());
        assertNull(session.getAttribute("analysisOwner"));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,()->store.get(id,owner)).getStatus());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,()->source.load(id,session)).getStatus());
    }
    @Test void failedLoginDoesNotDiscardCurrentResult() throws Exception {
        var session=browser();var user=user();login(session,user);UUID id=analyze(session);
        var token=csrf(session);
        mvc.perform(post("/auth/login").session(session).header(token.getHeaderName(),token.getToken())
                .contentType("application/json")
                .content("{\"email\":\""+user.getEmail()+"\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        assertEquals(id,source.load(id,session).analysisId());
        store.delete(id,(UUID)session.getAttribute("analysisOwner"));
    }
    @Test void otherBrowserCannotClaimResultEvenForSameAccount() throws Exception {
        var user=user();var first=browser();UUID id=analyze(first);login(first,user);
        var second=browser();login(second,user);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,()->source.load(id,second)).getStatus());
        assertEquals(id,source.load(id,first).analysisId());
        store.delete(id,(UUID)first.getAttribute("analysisOwner"));
    }
    @Test void logoutDoesNotRemoveAnotherBrowsersResult() throws Exception {
        var first=browser();login(first,user());UUID firstId=analyze(first);
        UUID firstOwner=(UUID)first.getAttribute("analysisOwner");
        var other=browser();login(other,user());UUID otherId=analyze(other);
        var token=csrf(first);
        mvc.perform(post("/auth/logout").session(first).header(token.getHeaderName(),token.getToken()))
                .andExpect(status().isNoContent());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,()->store.get(firstId,firstOwner)).getStatus());
        assertEquals(otherId,source.load(otherId,other).analysisId());
        store.delete(otherId,(UUID)other.getAttribute("analysisOwner"));
    }
    @Test void missingCsrfDoesNotDiscardAnalysisOrSession() throws Exception {
        var session=browser();login(session,user());UUID id=analyze(session);
        mvc.perform(post("/auth/logout").session(session)).andExpect(status().isForbidden());
        assertFalse(session.isInvalid());
        assertEquals(id,source.load(id,session).analysisId());
        store.delete(id,(UUID)session.getAttribute("analysisOwner"));
    }
    @Test void anonymousLogoutAlsoClearsTemporaryAnalysis() throws Exception {
        var session=browser();UUID id=analyze(session);
        UUID owner=(UUID)session.getAttribute("analysisOwner");
        var token=csrf(session);
        mvc.perform(post("/auth/logout").session(session).header(token.getHeaderName(),token.getToken()))
                .andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,()->store.get(id,owner)).getStatus());
    }
    @Test void logoutThenAnotherLoginCannotReusePreviousResult() throws Exception {
        var session=browser();login(session,user());UUID id=analyze(session);
        UUID owner=(UUID)session.getAttribute("analysisOwner");
        var token=csrf(session);
        mvc.perform(post("/auth/logout").session(session).header(token.getHeaderName(),token.getToken()))
                .andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
        var next=browser();login(next,user());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,()->source.load(id,next)).getStatus());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,()->store.get(id,owner)).getStatus());
    }
}
