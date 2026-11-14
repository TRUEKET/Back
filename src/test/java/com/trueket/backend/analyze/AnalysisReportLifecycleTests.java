package com.trueket.backend.analyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.analyze.dto.*;
import com.trueket.backend.analyze.service.*;
import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.identifier.repository.IdentifierRepository;
import com.trueket.backend.identifier.util.IdentifierHashService;
import com.trueket.backend.report.entity.*;
import com.trueket.backend.report.entity.enums.*;
import com.trueket.backend.report.repository.*;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Report registration HTTP API is not merged yet: reports are seeded through existing repositories. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:analysislifecycle;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AnalysisReportLifecycleTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired IdentifierRepository identifiers;
    @Autowired IdentifierHashService hashes;
    @Autowired ReportRepository reports;
    @Autowired ReportIdentifierRepository links;
    @Autowired AnalysisSessionStore sessions;
    @Autowired AnalysisReportSourceService sources;
    @Autowired AnalysisReportLookup lookup;
    @Autowired AnalysisPrompt prompt;
    @Autowired ChatStageService chat;
    static final AtomicInteger NUMBERS=new AtomicInteger(71000000);
    record Browser(User user,MockHttpSession session,UUID owner) {}
    record Seed(Report report,ReportIdentifier link,String phone) {}

    Browser browser() throws Exception {
        var user=users.saveAndFlush(User.create(UUID.randomUUID()+"@test.invalid",passwords.encode("password123"),
                UUID.randomUUID().toString(),"synthetic", "1234",OffsetDateTime.now()));
        var initial=mvc.perform(get("/auth/csrf")).andReturn();
        var session=(MockHttpSession)initial.getRequest().getSession();
        var token=(CsrfToken)initial.getRequest().getAttribute(CsrfToken.class.getName());
        mvc.perform(post("/auth/login").session(session).header(token.getHeaderName(),token.getToken())
                .contentType("application/json").content(new ObjectMapper().writeValueAsString(
                        Map.of("email",user.getEmail(),"password","password123"))))
                .andExpect(status().isOk());
        UUID owner=UUID.randomUUID();session.setAttribute("analysisOwner",owner);
        return new Browser(user,session,owner);
    }
    String phone() { return "010"+NUMBERS.incrementAndGet(); }
    Seed report(Browser reporter,String phone) {
        var identifier=identifiers.saveAndFlush(Identifier.create(IdentifierType.PHONE,
                hashes.hash(IdentifierType.PHONE,phone),"***1234","synthetic"));
        var json=new ObjectMapper();
        var report=reports.saveAndFlush(Report.create(reporter.user(),reporter.user().getPhoneHash(),ReportKind.FRAUD,
                TradeType.TRANSFER,json.createArrayNode(),json.createArrayNode(),null));
        return new Seed(report,links.saveAndFlush(ReportIdentifier.create(report,identifier)),phone);
    }
    AnalyzeResponse analyze(Browser viewer,String phone) throws Exception {
        String output="""
            {"readable":true,"detected_type":"transfer","type_mismatch":false,
             "sentences":[{"id":"s1","speaker":"SELLER","text":"공식 앱에서 확인해주세요","image_index":1}],
             "signals":[],"extracted":{"accounts":[],"phones":["%s"],"x_accounts":[],"links":[]},
             "other_suspicious":null,"sensitive_values":["%s"]}
            """.formatted(phone,phone);
        var service=new AnalyzeService(new CaptureProcessor(),prompt,(p,i,s,t)->output,new ModelExtractionParser(),
                chat,new FinalStageService(),lookup,new AnalysisRedactor(),sessions,new ConversationDetailsService(),Duration.ofSeconds(2));
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB),"png",bytes);
        return service.analyze(List.of(new MockMultipartFile("captures","synthetic.png","image/png",bytes.toByteArray())),"",viewer.owner());
    }
    @Test void newlySeededReportChangesNewAnalysisButNeverRewritesInitialPermissions() throws Exception {
        var viewer=browser();var reporter=browser();String phone=phone();
        var initial=analyze(viewer,phone);
        assertEquals(Stage.SAFE,initial.assessment().finalStage());
        report(reporter,phone);
        var original=sources.load(initial.analyzeId(),viewer.session());
        assertFalse(original.showReportButton());assertFalse(original.showSuspicionReport());
        var next=analyze(viewer,phone);
        assertEquals(Stage.DANGER,next.assessment().finalStage());
        var source=sources.load(next.analyzeId(),viewer.session());
        assertTrue(source.showReportButton());assertFalse(source.showSuspicionReport());
    }
    @Test void privateAndHiddenChangesAreReflectedInNextAnalysis() throws Exception {
        var viewer=browser();var seed=report(browser(),phone());
        assertEquals(Stage.DANGER,analyze(viewer,seed.phone()).assessment().finalStage());
        seed.report().makePrivate();reports.saveAndFlush(seed.report());
        assertEquals(Stage.SAFE,analyze(viewer,seed.phone()).assessment().finalStage());
        seed.report().publish();reports.saveAndFlush(seed.report());
        assertEquals(Stage.DANGER,analyze(viewer,seed.phone()).assessment().finalStage());
        seed.link().hide();links.saveAndFlush(seed.link());
        var hidden=analyze(viewer,seed.phone());
        assertEquals(Stage.SAFE,hidden.assessment().finalStage());
        assertFalse(sources.load(hidden.analyzeId(),viewer.session()).showReportButton());
    }
    @Test void realWithdrawalHttpRemovesReportFromNextAnalysisButPreservesOtherUsersSnapshot() throws Exception {
        var reporter=browser();var viewer=browser();var seed=report(reporter,phone());
        var before=analyze(viewer,seed.phone());
        assertEquals(Stage.DANGER,before.assessment().finalStage());
        var csrfResult=mvc.perform(get("/auth/csrf").session(reporter.session())).andReturn();
        var csrf=(CsrfToken)csrfResult.getRequest().getAttribute(CsrfToken.class.getName());
        mvc.perform(delete("/me").session(reporter.session()).header(csrf.getHeaderName(),csrf.getToken()))
                .andExpect(status().isNoContent());
        assertFalse(users.existsById(reporter.user().getId()));assertFalse(reports.existsById(seed.report().getId()));
        assertTrue(users.existsById(viewer.user().getId()));
        assertTrue(sources.load(before.analyzeId(),viewer.session()).showReportButton());
        var after=analyze(viewer,seed.phone());
        assertEquals(Stage.SAFE,after.assessment().finalStage());
        assertNull(after.assessment().lookupWarning());
        assertFalse(sources.load(after.analyzeId(),viewer.session()).showReportButton());
    }
}
