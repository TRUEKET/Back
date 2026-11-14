package com.trueket.backend.feedback;

import com.trueket.backend.analyze.dto.ModelExtraction;
import com.trueket.backend.analyze.service.AnalysisSessionStore;
import com.trueket.backend.feedback.entity.FeedbackKind;
import com.trueket.backend.feedback.repository.FeedbackRepository;
import com.trueket.backend.feedback.service.FeedbackService;
import com.trueket.backend.global.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FeedbackFlowTests {
    @Autowired MockMvc mvc;
    @Autowired AnalysisSessionStore sessions;
    @Autowired FeedbackService feedback;
    @Autowired FeedbackRepository records;
    @Autowired PlatformTransactionManager transactions;

    private record Browser(MockHttpSession session, CsrfToken csrf, UUID owner) {}
    private Browser browser() throws Exception {
        var initial = mvc.perform(get("/auth/csrf")).andReturn();
        var session = (MockHttpSession) initial.getRequest().getSession();
        UUID owner = UUID.randomUUID();
        session.setAttribute("analysisOwner", owner);
        return new Browser(session, (CsrfToken) initial.getRequest().getAttribute(CsrfToken.class.getName()), owner);
    }
    private AnalysisSessionStore.Snapshot snapshot() {
        var extracted = new ModelExtraction.Extracted(List.of("private-account"), List.of(), List.of(), List.of());
        return new AnalysisSessionStore.Snapshot(new ModelExtraction(true, "transfer", false,
                List.of(), List.of(), extracted, "private-source-text", List.of("private-account")), List.of());
    }
    private UUID analysis(Browser browser) { return sessions.save(browser.owner(), snapshot()); }
    private ResultActions submit(Browser browser, UUID id, String body) throws Exception {
        return mvc.perform(post("/analyze/{id}/feedback", id).session(browser.session())
            .header(browser.csrf().getHeaderName(), browser.csrf().getToken())
            .contentType("application/json").content(body));
    }

    @Test void anonymousHelpfulFeedbackSavesOnlyIdKindAndTime() throws Exception {
        var browser = browser();
        UUID id = analysis(browser);
        submit(browser, id, "{\"kind\":\"HELPFUL\"}").andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.message").value("소중한 의견 감사합니다"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        var stored = records.findAll().stream().filter(row -> row.getAnalyzeId().equals(id)).findFirst().orElseThrow();
        assertEquals(FeedbackKind.HELPFUL, stored.getKind());
        assertNotNull(stored.getCreatedAt());

    }

    @Test void incorrectFeedbackAppearsInTeamReviewQueue() throws Exception {
        var browser = browser();
        UUID id = analysis(browser);
        submit(browser, id, "{\"kind\":\"INCORRECT\"}").andExpect(status().isCreated());
        assertTrue(records.findByKindOrderByCreatedAtDescIdDesc(FeedbackKind.INCORRECT, PageRequest.of(0,100))
                .stream().anyMatch(row -> row.getAnalyzeId().equals(id)));
    }

    @Test void repeatedOrChangedVoteIsRejected() throws Exception {
        var browser = browser();
        UUID id = analysis(browser);
        submit(browser, id, "{\"kind\":\"HELPFUL\"}").andExpect(status().isCreated());
        submit(browser, id, "{\"kind\":\"HELPFUL\"}").andExpect(status().isConflict());
        submit(browser, id, "{\"kind\":\"INCORRECT\"}").andExpect(status().isConflict());
        assertEquals(1, records.countByAnalyzeId(id));
    }

    @Test void anotherBrowserCannotVoteEvenWithKnownAnalysisId() throws Exception {
        var owner = browser();
        UUID id = analysis(owner);
        submit(browser(), id, "{\"kind\":\"INCORRECT\"}").andExpect(status().isNotFound());
        assertEquals(0, records.countByAnalyzeId(id));
    }

    @Test void deletedOrReplacedAnalysisIsRejected() throws Exception {
        var browser = browser();
        UUID deleted = analysis(browser);
        sessions.delete(deleted, browser.owner());
        submit(browser, deleted, "{\"kind\":\"HELPFUL\"}").andExpect(status().isNotFound());
        UUID replaced = analysis(browser);
        analysis(browser);
        submit(browser, replaced, "{\"kind\":\"HELPFUL\"}").andExpect(status().isNotFound());
    }

    @Test void expiredAnalysisIsRejectedWithoutSaving() {
        Instant start = Instant.parse("2026-11-13T00:00:00Z");
        class MutableClock extends Clock {
            Instant now = start;
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now; }
        }
        var clock = new MutableClock();
        var store = new AnalysisSessionStore(clock);
        UUID owner = UUID.randomUUID();
        UUID id = store.save(owner, snapshot());
        clock.now = start.plusSeconds(900);
        var service = new FeedbackService(store, records, transactions);
        assertThrows(ApiException.class, () -> service.submit(id, owner, FeedbackKind.HELPFUL));
        assertEquals(0, records.countByAnalyzeId(id));
    }

    @Test void invalidKindAndMissingCsrfCannotWrite() throws Exception {
        var browser = browser();
        UUID id = analysis(browser);
        submit(browser, id, "{}").andExpect(status().isBadRequest());
        submit(browser, id, "{\"kind\":\"OTHER\"}").andExpect(status().isBadRequest());
        mvc.perform(post("/analyze/{id}/feedback",id).session(browser.session()).contentType("application/json")
            .content("{\"kind\":\"HELPFUL\"}")).andExpect(status().isForbidden());
        assertEquals(0, records.countByAnalyzeId(id));
    }

    @Test void parallelVotesCommitExactlyOneRecord() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID id = sessions.save(owner, snapshot());
        var executor = Executors.newFixedThreadPool(4);
        var ready = new CountDownLatch(4);
        var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i=0;i<4;i++) results.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test start timed out");
                try { feedback.submit(id,owner,FeedbackKind.HELPFUL); return true; }
                catch (ApiException e) { assertEquals(org.springframework.http.HttpStatus.CONFLICT,e.getStatus()); return false; }
            }));
            assertTrue(ready.await(5,TimeUnit.SECONDS));
            start.countDown();
            int accepted = 0;
            for (var result : results) if (result.get(10,TimeUnit.SECONDS)) accepted++;
            assertEquals(1,accepted);
            assertEquals(1,records.countByAnalyzeId(id));
        } finally { executor.shutdownNow(); }
    }
}
