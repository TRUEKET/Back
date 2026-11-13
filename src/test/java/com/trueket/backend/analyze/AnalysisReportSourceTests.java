package com.trueket.backend.analyze;

import com.trueket.backend.analyze.dto.*;
import com.trueket.backend.analyze.service.*;
import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisReportSourceTests {
    final UUID owner = UUID.randomUUID();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC);
    final AnalysisSessionStore store = new AnalysisSessionStore(clock);
    final UserRepository users = mock(UserRepository.class);
    final AnalysisReportSourceService service = new AnalysisReportSourceService(store, users,
            new ChatStageService(new SignalDictionary()), new AnalysisRedactor());
    final MockHttpSession session = new MockHttpSession();
    UUID id;

    AnalysisReportSourceTests() throws Exception {}

    @BeforeEach void setup() {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("7",null,List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);
        session.setAttribute("analysisOwner",owner);
        when(users.existsByIdAndStatus(7L,"ACTIVE")).thenReturn(true);
        String text="먼저 01012345678로 연락하고 선입금해 주세요";
        var extraction=new ModelExtraction(true,"transfer",false,
                List.of(new ModelExtraction.SourceSentence("s1",AnalysisInput.Speaker.SELLER,text,1)),
                List.of(new ModelExtraction.SourceSignal("S-A2","s1",text,"선입금 요구")),
                new ModelExtraction.Extracted(List.of(),List.of("01012345678"),List.of(),List.of()),
                null,List.of("01012345678"));
        id=store.save(owner,new AnalysisSessionStore.Snapshot(extraction,List.of()));
    }
    @Test void returnsInternalIdentifiersButMasksAcceptedEvidence() {
        var result=service.load(id,session);
        assertEquals(7L,result.userId());
        assertEquals(List.of("01012345678"),result.identifiers().phones());
        assertFalse(result.evidence().isEmpty());
        assertTrue(result.evidence().stream().noneMatch(e->e.quote().contains("01012345678")));
        assertFalse(result.toString().contains("01012345678"));
        assertThrows(UnsupportedOperationException.class,()->result.identifiers().phones().add("other"));
        assertThrows(UnsupportedOperationException.class,()->result.evidence().clear());
    }
    @Test void rejectsAnonymousAndInactiveUsers() {
        assertStatus(HttpStatus.UNAUTHORIZED,()->service.load(id,null));
        session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertStatus(HttpStatus.UNAUTHORIZED,()->service.load(id,session));
    }
    @Test void rejectsAccountNoLongerActive() {
        when(users.existsByIdAndStatus(7L,"ACTIVE")).thenReturn(false);
        assertStatus(HttpStatus.UNAUTHORIZED,()->service.load(id,session));
    }
    @Test void rejectsOtherSessionOwnerAndMissingOwner() {
        session.setAttribute("analysisOwner",UUID.randomUUID());
        assertStatus(HttpStatus.NOT_FOUND,()->service.load(id,session));
        session.removeAttribute("analysisOwner");
        assertStatus(HttpStatus.NOT_FOUND,()->service.load(id,session));
    }
    @Test void rejectsDeletedAndUnknownResults() {
        assertStatus(HttpStatus.NOT_FOUND,()->service.load(UUID.randomUUID(),session));
        store.delete(id,owner);
        assertStatus(HttpStatus.NOT_FOUND,()->service.load(id,session));
    }
    @Test void newerAnalysisReplacesOldResult() {
        var snapshot=store.get(id,owner);
        UUID newer=store.save(owner,snapshot);
        assertStatus(HttpStatus.NOT_FOUND,()->service.load(id,session));
        assertEquals(newer,service.load(newer,session).analysisId());
    }
    @Test void rejectsExpiredResult() throws Exception {
        // A clock that advances after saving exercises the actual expiration boundary.
        final Instant[] time={clock.instant()};
        Clock advancing=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;} public Instant instant(){return time[0];}};
        var expiring=new AnalysisSessionStore(advancing);
        UUID expires=expiring.save(owner,new AnalysisSessionStore.Snapshot(store.get(id,owner).extraction(),List.of()));
        var source=new AnalysisReportSourceService(expiring,users,new ChatStageService(new SignalDictionary()),new AnalysisRedactor());
        time[0]=time[0].plusSeconds(900);
        assertStatus(HttpStatus.NOT_FOUND,()->source.load(expires,session));
    }
    void assertStatus(HttpStatus expected,Runnable call){assertEquals(expected,assertThrows(ApiException.class,call::run).getStatus());}
}
