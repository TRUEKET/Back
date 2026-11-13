package com.trueket.backend.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.trueket.backend.analyze.dto.ModelExtraction;
import com.trueket.backend.analyze.service.AnalysisSessionStore;
import com.trueket.backend.dispute.entity.*;
import com.trueket.backend.dispute.entity.enums.DisputeReason;
import com.trueket.backend.dispute.repository.*;
import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.identifier.repository.IdentifierRepository;
import com.trueket.backend.report.entity.*;
import com.trueket.backend.report.entity.enums.*;
import com.trueket.backend.report.repository.*;
import com.trueket.backend.user.dto.AuthDtos.*;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.user.repository.*;
import com.trueket.backend.user.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import java.net.InetSocketAddress;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:withdrawal;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class WithdrawalFlowTests {
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final List<String> DELETED = new CopyOnWriteArrayList<>();
    static final AtomicInteger NUMBERS = new AtomicInteger(900000);
    static final AtomicLong IDS = new AtomicLong(1000);
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpServer STORAGE;
    static {
        try {
            STORAGE=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            STORAGE.createContext("/storage/v1/object/",exchange -> {
                assertEquals("DELETE",exchange.getRequestMethod());
                assertEquals("Bearer fake-service-key",exchange.getRequestHeaders().getFirst("Authorization"));
                var body=JSON.readTree(exchange.getRequestBody());
                for(var key:body.path("prefixes")) DELETED.add(exchange.getRequestURI().getPath()+":"+key.asText());
                byte[] response="[]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(STATUS.get(),response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            STORAGE.start();
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void storage(DynamicPropertyRegistry properties) {
        properties.add("storage.supabase-url",()->"http://127.0.0.1:"+STORAGE.getAddress().getPort());
        properties.add("storage.service-role-key",()->"fake-service-key");
        properties.add("storage.evidence-bucket",()->"evidence-test");
        properties.add("storage.trueshot-masked-bucket",()->"masked-test");
        properties.add("storage.trueshot-original-bucket",()->"original-test");
    }
    @AfterAll static void stop() { STORAGE.stop(0); }
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired ReportRepository reports;
    @Autowired EvidenceRepository evidence;
    @Autowired IdentifierRepository identifiers;
    @Autowired ReportIdentifierRepository links;
    @Autowired DisputeRepository disputes;
    @Autowired DisputeItemRepository items;
    @Autowired JdbcTemplate jdbc;
    @Autowired AnalysisSessionStore analyses;
    @Autowired WithdrawalRepository owned;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @BeforeEach void prepare() {
        STATUS.set(200); DELETED.clear();
        jdbc.execute("create table if not exists trueshot_requests(id bigint primary key,user_id bigint not null references users(id))");
        jdbc.execute("create table if not exists trueshot_photos(id bigint primary key,request_id bigint not null references trueshot_requests(id),masked_image text,original_storage_key text)");
        jdbc.execute("create table if not exists withdrawal_test_block(user_id bigint references users(id))");
        jdbc.update("delete from withdrawal_test_block");
    }
    private record Account(User user,String phone) {}
    private record Browser(MockHttpSession session,CsrfToken csrf) {}
    private Account account() {
        String phone="010"+String.format("%08d",NUMBERS.incrementAndGet());
        var sent=auth.send(new PhoneSend(phone));
        auth.verify(new PhoneVerify(sent.verificationId(),phone,sent.demoSms().code()));
        return new Account(auth.signup(new Signup(phone+"@example.com","password123",phone,sent.verificationId(),true)),phone);
    }
    private Browser login(Account account) throws Exception {
        var initial=mvc.perform(get("/auth/csrf")).andReturn();
        var session=(MockHttpSession)initial.getRequest().getSession();
        var token=(CsrfToken)initial.getRequest().getAttribute(CsrfToken.class.getName());
        mvc.perform(post("/auth/login").session(session).header(token.getHeaderName(),token.getToken())
                .contentType("application/json").content(JSON.writeValueAsString(Map.of("email",account.user().getEmail(),"password","password123"))))
            .andExpect(status().isOk());
        var next=mvc.perform(get("/auth/csrf").session(session)).andReturn();
        return new Browser(session,(CsrfToken)next.getRequest().getAttribute(CsrfToken.class.getName()));
    }
    private ResultActions withdraw(Browser browser) throws Exception {
        return mvc.perform(delete("/me").session(browser.session()).header(browser.csrf().getHeaderName(),browser.csrf().getToken()));
    }
    private Report report(Account account,String key) {
        var report=reports.saveAndFlush(Report.create(account.user(),account.user().getPhoneHash(),ReportKind.SUSPICION,
            TradeType.TRANSFER,JSON.createArrayNode(),JSON.createArrayNode(),null));
        if(key!=null) evidence.saveAndFlush(Evidence.create(report,EvidenceKind.EVIDENCE_QUOTE,key,null,OffsetDateTime.now().plusYears(1)));
        return report;
    }
    private long photos(Account account,String masked,String original) {
        long id=IDS.incrementAndGet();
        jdbc.update("insert into trueshot_requests(id,user_id) values(?,?)",id,account.user().getId());
        jdbc.update("insert into trueshot_photos(id,request_id,masked_image,original_storage_key) values(?,?,?,?)",id,id,masked,original);
        return id;
    }

    @Test void deletesOwnedRowsAndFilesButPreservesOtherAccountsAndSharedIdentifiers() throws Exception {
        var owner=account(); var other=account();
        var ownedReport=report(owner,"owner/evidence.png");
        var otherReport=report(other,"other/evidence.png");
        long ownedRequest=photos(owner,"owner/masked.png","owner/original.png");
        long otherRequest=photos(other,"other/masked.png","other/original.png");
        var identifier=identifiers.saveAndFlush(Identifier.create(IdentifierType.ACCOUNT,UUID.randomUUID().toString(),"***1234","encrypted"));
        links.saveAndFlush(ReportIdentifier.create(ownedReport,identifier));
        links.saveAndFlush(ReportIdentifier.create(otherReport,identifier));
        var dispute=disputes.saveAndFlush(Dispute.create("TK-test-"+UUID.randomUUID(),identifier,DisputeReason.NORMAL,null,"seller@example.com",JSON.createArrayNode()));
        var ownItem=items.saveAndFlush(DisputeItem.create(dispute,ownedReport));
        var otherItem=items.saveAndFlush(DisputeItem.create(dispute,otherReport));
        // Phone-change proofs may refer to a different number; remove them by purpose too.
        var changed=auth.sendPhoneChange(owner.user().getId(),new PhoneSend("010"+String.format("%08d",NUMBERS.incrementAndGet())));
        var browser=login(owner);
        UUID analysisOwner=UUID.randomUUID(); browser.session().setAttribute("analysisOwner",analysisOwner);
        UUID analysis=analyses.save(analysisOwner,new AnalysisSessionStore.Snapshot(
            new ModelExtraction(false,"etc",false,List.of(),List.of(),new ModelExtraction.Extracted(List.of(),List.of(),List.of(),List.of()),null,List.of()),List.of()));
        withdraw(browser).andExpect(status().isNoContent()).andExpect(header().string("Cache-Control","no-store"));
        assertFalse(users.existsById(owner.user().getId())); assertTrue(users.existsById(other.user().getId()));
        assertFalse(reports.existsById(ownedReport.getId())); assertTrue(reports.existsById(otherReport.getId()));
        assertFalse(items.existsById(ownItem.getId())); assertTrue(items.existsById(otherItem.getId()));
        assertTrue(identifiers.existsById(identifier.getId())); assertTrue(disputes.existsById(dispute.getId()));
        assertEquals(0,jdbc.queryForObject("select count(*) from trueshot_requests where id=?",Integer.class,ownedRequest));
        assertEquals(1,jdbc.queryForObject("select count(*) from trueshot_requests where id=?",Integer.class,otherRequest));
        assertEquals(0,jdbc.queryForObject("select count(*) from phone_verifications where id=?",Integer.class,changed.verificationId()));
        assertEquals(0,jdbc.queryForObject("select count(*) from phone_verifications where phone_hash=?",Integer.class,owner.user().getPhoneHash()));
        assertEquals(Set.of("/storage/v1/object/evidence-test:owner/evidence.png","/storage/v1/object/masked-test:owner/masked.png","/storage/v1/object/original-test:owner/original.png"),Set.copyOf(DELETED));
        assertTrue(browser.session().isInvalid());
        assertThrows(com.trueket.backend.global.exception.ApiException.class,()->analyses.get(analysis,analysisOwner));
    }

    @Test void storageFailureKeepsAccountRowsAndSessionAndCanRetry() throws Exception {
        var owner=account(); var report=report(owner,"retry/evidence.png");
        var browser=login(owner);
        STATUS.set(503);
        withdraw(browser).andExpect(status().isServiceUnavailable());
        assertTrue(users.existsById(owner.user().getId())); assertTrue(reports.existsById(report.getId()));
        assertFalse(browser.session().isInvalid());
        STATUS.set(200);
        withdraw(browser).andExpect(status().isNoContent());
        assertFalse(users.existsById(owner.user().getId()));
    }

    @Test void missingStorageSettingsNeverPretendToDeletePhotos() {
        var owner=account(); report(owner,"needs-config/evidence.png");
        var service=new WithdrawalService(users,owned,new com.trueket.backend.global.storage.SupabaseStorageDeletion("","","","",""));
        assertThrows(com.trueket.backend.global.exception.ApiException.class,()->new org.springframework.transaction.support.TransactionTemplate(transactions)
            .execute(status->{service.withdraw(owner.user().getId());return null;}));
        assertTrue(users.existsById(owner.user().getId())); assertTrue(DELETED.isEmpty());
    }

    @Test void noPhotosDoesNotNeedStorageSettings() {
        var owner=account();
        var service=new WithdrawalService(users,owned,new com.trueket.backend.global.storage.SupabaseStorageDeletion("","","","",""));
        new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{service.withdraw(owner.user().getId());return null;});
        assertFalse(users.existsById(owner.user().getId())); assertTrue(DELETED.isEmpty());
    }

    @Test void databaseFailureRollsBackRowsAfterStorageCall() throws Exception {
        var owner=account(); var report=report(owner,"db-retry/evidence.png");
        jdbc.update("insert into withdrawal_test_block(user_id) values(?)",owner.user().getId());
        var browser=login(owner);
        withdraw(browser).andExpect(status().isServiceUnavailable());
        assertTrue(users.existsById(owner.user().getId())); assertTrue(reports.existsById(report.getId()));
        assertFalse(DELETED.isEmpty());
        jdbc.update("delete from withdrawal_test_block where user_id=?",owner.user().getId());
        withdraw(browser).andExpect(status().isNoContent());
    }

    @Test void staleSessionsAreRejectedAndOtherUsersRemainLoggedIn() throws Exception {
        var owner=account(); var other=account();
        var first=login(owner); var second=login(owner); var otherBrowser=login(other);
        withdraw(first).andExpect(status().isNoContent());
        mvc.perform(get("/me").session(second.session())).andExpect(status().isUnauthorized());
        assertTrue(second.session().isInvalid());
        mvc.perform(get("/me").session(otherBrowser.session())).andExpect(status().isOk());
        mvc.perform(get("/me")).andExpect(status().isUnauthorized());
        var resent=auth.send(new PhoneSend(owner.phone()));
        assertNotNull(resent.verificationId());
    }

    @Test void anonymousOrMissingCsrfCannotDeleteAndRequestCannotChooseAnotherUser() throws Exception {
        var owner=account(); var other=account(); var browser=login(owner);
        mvc.perform(delete("/me").session(browser.session())).andExpect(status().isForbidden());
        var initial=mvc.perform(get("/auth/csrf")).andReturn();
        var token=(CsrfToken)initial.getRequest().getAttribute(CsrfToken.class.getName());
        mvc.perform(delete("/me").session((MockHttpSession)initial.getRequest().getSession()).header(token.getHeaderName(),token.getToken()))
            .andExpect(status().isUnauthorized());
        mvc.perform(delete("/me").session(browser.session()).header(browser.csrf().getHeaderName(),browser.csrf().getToken())
            .contentType("application/json").content("{\"id\":"+other.user().getId()+"}"))
            .andExpect(status().isNoContent());
        assertTrue(users.existsById(other.user().getId())); assertFalse(users.existsById(owner.user().getId()));
    }

    @Test void sharedFileReferenceAndUnexpectedUrlStopBeforeDeletingAnything() throws Exception {
        var owner=account(); var other=account();
        report(owner,"shared/file.png"); report(other,"shared/file.png");
        withdraw(login(owner)).andExpect(status().isConflict());
        assertTrue(DELETED.isEmpty()); assertTrue(users.existsById(owner.user().getId()));
        var urlOwner=account(); report(urlOwner,"https://unknown.example/file.png");
        withdraw(login(urlOwner)).andExpect(status().isServiceUnavailable());
        assertTrue(DELETED.isEmpty()); assertTrue(users.existsById(urlOwner.user().getId()));
    }
}
