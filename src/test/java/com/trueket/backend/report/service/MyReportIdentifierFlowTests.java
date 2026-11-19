package com.trueket.backend.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.identifier.repository.IdentifierRepository;
import com.trueket.backend.identifier.service.IdentifierEncryptionService;
import com.trueket.backend.identifier.util.*;
import com.trueket.backend.report.entity.*;
import com.trueket.backend.report.entity.enums.*;
import com.trueket.backend.report.repository.*;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:owned-report-values;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","dispute.mail.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class MyReportIdentifierFlowTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ReportRepository reports;
    @Autowired IdentifierRepository identifiers;
    @Autowired ReportIdentifierRepository links;
    @Autowired IdentifierEncryptionService encryption;
    @Autowired IdentifierHashService hashes;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;
    private final ObjectMapper json=new ObjectMapper();
    private User owner,other;
    private Report ownReport,foreignReport;
    private Identifier account;

    @BeforeEach void setup() {
        owner=user();other=user();ownReport=report(owner);foreignReport=report(other);
        account=identifier(ownReport,"신한 110-22-123456",false);
        identifier(ownReport,"01012345678",false);identifier(ownReport,"@seller_test",false);
        identifier(ownReport,"01099998888",true);
        identifier(foreignReport,"우리 123456789012",false);
    }
    private User user() {
        return users.saveAndFlush(User.create(UUID.randomUUID()+"@example.invalid",passwords.encode("Examplepass123!"),
                UUID.randomUUID().toString(),"synthetic","5678",OffsetDateTime.now()));
    }
    private Report report(User user) {
        return reports.saveAndFlush(Report.create(user,user.getPhoneHash(),ReportKind.SUSPICION,TradeType.TRANSFER,
                JsonNodeFactory.instance.arrayNode(),JsonNodeFactory.instance.arrayNode(),null));
    }
    private Identifier identifier(Report report,String input,boolean hidden) {
        var n=IdentifierNormalizer.normalize(input);
        var identifier=identifiers.saveAndFlush(Identifier.create(n.type(),hashes.hash(n.type(),n.value()),
                IdentifierMasker.mask(n.type(),n.value()),encryption.encrypt(n.value())));
        var link=ReportIdentifier.create(report,identifier);if(hidden)link.hide();links.saveAndFlush(link);return identifier;
    }
    private MockHttpSession login(User user) throws Exception {
        var initial=mvc.perform(get("/auth/csrf")).andReturn();var session=(MockHttpSession)initial.getRequest().getSession();
        var csrf=(CsrfToken)initial.getRequest().getAttribute(CsrfToken.class.getName());
        mvc.perform(post("/auth/login").session(session).header(csrf.getHeaderName(),csrf.getToken()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email",user.getEmail(),"password","Examplepass123!"))))
                .andExpect(status().isOk());return session;
    }
    private void assertFullValues(com.fasterxml.jackson.databind.JsonNode values) {
        Map<String,String> byType=new HashMap<>();
        for(var v:values){byType.put(v.path("type").asText(),v.path("value").asText());assertFalse(v.path("value").asText().contains("*"));}
        assertEquals(Map.of("ACCOUNT","신한 11022123456","PHONE","010-1234-5678","SNS","@seller_test"),byType);
        assertEquals(3,values.size());
    }
    @Test void ownListAndDetailReturnAllValuesWithNoStore() throws Exception {
        var session=login(owner);
        var detail=mvc.perform(get("/me/reports/{id}",ownReport.getId()).session(session)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        assertFullValues(json.readTree(detail).path("data").path("identifiers"));
        var list=mvc.perform(get("/me/reports").session(session).param("userId",other.getId().toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andReturn().getResponse().getContentAsString();
        var content=json.readTree(list).path("data").path("content");assertEquals(1,content.size());
        assertEquals(ownReport.getId(),content.get(0).path("reportId").asLong());assertFullValues(content.get(0).path("identifiers"));
        assertFalse(list.contains("123456789012"));assertFalse(detail.contains("010-9999-8888"));
    }
    @Test void anonymousAndOtherUsersCannotReadOwnerPlaintext() throws Exception {
        mvc.perform(get("/me/reports")).andExpect(status().isUnauthorized());
        mvc.perform(get("/me/reports/{id}",ownReport.getId())).andExpect(status().isUnauthorized());
        // Even corrupt ciphertext must not be inspected before ownership succeeds.
        jdbc.update("update identifiers set value_enc='broken-ciphertext' where id=?",account.getId());
        entityManager.clear();
        mvc.perform(get("/me/reports/{id}",ownReport.getId()).session(login(other))).andExpect(status().isNotFound());
    }
    @Test void publicDetailRemainsMaskedWithoutPlaintextField() throws Exception {
        var body=mvc.perform(get("/lookup/{id}",account.getId())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("******"));assertFalse(body.contains("11022123456"));
        assertFalse(json.readTree(body).path("data").has("value"));
    }
    @Test void corruptCiphertextFailsClosedInsteadOfPretendingMaskedDataIsOriginal() throws Exception {
        jdbc.update("update identifiers set value_enc='broken-ciphertext' where id=?",account.getId());
        entityManager.clear();
        mvc.perform(get("/me/reports/{id}",ownReport.getId()).session(login(owner))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("신고 식별자 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요."));
    }
}
