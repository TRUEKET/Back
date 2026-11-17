package com.trueket.backend.analyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.analyze.service.AnalysisReportSourceService;
import com.trueket.backend.global.ai.AnalysisModelClient;
import com.trueket.backend.report.dto.ReportEvidenceAnalysis;
import com.trueket.backend.report.service.*;
import com.trueket.backend.report.repository.*;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import java.time.OffsetDateTime;
import java.util.*;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:fraudflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","dispute.mail.enabled=false"})
@AutoConfigureMockMvc
class AnalysisFraudEvidenceFlowTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired AnalysisReportSourceService sources;
    @Autowired ReportRepository reports;
    @Autowired EvidenceRepository evidence;
    @MockitoBean AnalysisModelClient model;
    @MockitoBean ReportEvidenceAiService receiptAi;
    @MockitoBean ReportEvidencePrivacyValidator privacy;
    @MockitoBean SupabaseReportEvidenceStore storage;
    final ObjectMapper json=new ObjectMapper();
    MockHttpSession session;
    CsrfToken csrf;
    UUID analysisId;
    long userId;
    MockMultipartFile png;
    @BeforeEach void setup() throws Exception {
        var user=users.saveAndFlush(User.create(UUID.randomUUID()+"@test.invalid",passwords.encode("password123"),
                UUID.randomUUID().toString(),"synthetic","1234",OffsetDateTime.now()));
        userId=user.getId();
        var initial=mvc.perform(get("/auth/csrf")).andReturn();
        session=(MockHttpSession)initial.getRequest().getSession();
        var first=(CsrfToken)initial.getRequest().getAttribute(CsrfToken.class.getName());
        mvc.perform(post("/auth/login").session(session).header(first.getHeaderName(),first.getToken())
                .contentType("application/json").content(json.writeValueAsString(Map.of("email",user.getEmail(),"password","password123"))))
                .andExpect(status().isOk());
        csrf=(CsrfToken)mvc.perform(get("/auth/csrf").session(session)).andReturn().getRequest().getAttribute(CsrfToken.class.getName());
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(400,200,BufferedImage.TYPE_INT_RGB),"png",bytes);
        png=new MockMultipartFile("file","synthetic.png","image/png",bytes.toByteArray());
        when(model.generate(anyString(),anyList(),any(),any())).thenReturn("""
          {"readable":true,"detected_type":"transfer","type_mismatch":false,
           "sentences":[{"id":"s1","speaker":"SELLER","text":"로그인 인증번호를 알려주세요. 신한 111-111-1-111111","image_index":1}],
           "signals":[{"id":"S-A2","sentence_id":"s1","quote":"로그인 인증번호를 알려주세요.","reason":"인증번호 요구"}],
           "extracted":{"accounts":["신한 1111111111111"],"phones":[],"x_accounts":[],"links":[]},
           "other_suspicious":null,"sensitive_values":["신한 111-111-1-111111","111-111-1-111111","1111111111111"]}
          """);
        var capture=new MockMultipartFile("captures","synthetic.png","image/png",bytes.toByteArray());
        String response=mvc.perform(multipart("/analyze").file(capture).session(session)
                .header(csrf.getHeaderName(),csrf.getToken())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var analyzed=json.readTree(response);
        assertEquals("***1111",analyzed.path("identifiers").get(0).path("value").asText());
        assertFalse(response.contains("1111111111111"));
        analysisId=UUID.fromString(analyzed.path("analyzeId").asText());
        assertEquals(List.of("신한 1111111111111"),sources.load(analysisId,session).identifiers().accounts());
        when(receiptAi.analyze(any())).thenReturn(new ReportEvidenceAnalysis(true,"신한은행","111-111-1-111111",
                new ReportEvidenceAnalysis.BoundingBox(.1,.1,.9,.4),.99));
        when(storage.createPendingKey(anyLong())).thenAnswer(i->"pending/"+i.getArgument(0)+"/"+UUID.randomUUID()+".png");
        when(storage.createReportKey(anyLong())).thenAnswer(i->"reports/"+i.getArgument(0)+"/"+UUID.randomUUID()+".png");
    }
    @Test void maskedDisplayMustNotBeSubmittedAsFullAccount() throws Exception {
        mvc.perform(multipart("/reports/evidence").file(png).param("analysisId",analysisId.toString())
                .param("account","***1111").session(session).header(csrf.getHeaderName(),csrf.getToken()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(receiptAi,storage);
    }
    @Test void accountIndexVerifiesReceiptAndCreatesFraudWithConsumedEvidence() throws Exception {
        var response=mvc.perform(multipart("/reports/evidence").file(png).param("analysisId",analysisId.toString())
                .param("accountIndex","0").session(session).header(csrf.getHeaderName(),csrf.getToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.verified").value(true))
                .andReturn().getResponse().getContentAsString();
        String token=json.readTree(response).path("data").path("evidenceToken").asText();
        var created=mvc.perform(post("/reports").session(session).header(csrf.getHeaderName(),csrf.getToken())
                .contentType("application/json").content(json.writeValueAsString(Map.of("analysisId",analysisId,
                        "kind","FRAUD","evidenceToken",token))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long reportId=json.readTree(created).path("data").path("reportId").asLong();
        assertTrue(reports.existsById(reportId));
        assertEquals(1,evidence.findAll().stream().filter(e->e.getReport().getId().equals(reportId)).count());
        verify(storage).copyPendingToReport(anyString(),anyString());
        verify(storage).deletePending(anyString());
        mvc.perform(post("/reports").session(session).header(csrf.getHeaderName(),csrf.getToken())
                .contentType("application/json").content(json.writeValueAsString(Map.of("analysisId",analysisId,
                        "kind","FRAUD","evidenceToken",token))))
                .andExpect(status().is4xxClientError());
    }
    @Test void wrongReceiptAccountAndOutOfRangeIndexNeverUpload() throws Exception {
        mvc.perform(multipart("/reports/evidence").file(png).param("analysisId",analysisId.toString())
                .param("accountIndex","99").session(session).header(csrf.getHeaderName(),csrf.getToken()))
                .andExpect(status().isBadRequest());
        when(receiptAi.analyze(any())).thenReturn(new ReportEvidenceAnalysis(true,"신한","2222222222222",
                new ReportEvidenceAnalysis.BoundingBox(.1,.1,.9,.4),.99));
        mvc.perform(multipart("/reports/evidence").file(png).param("analysisId",analysisId.toString())
                .param("accountIndex","0").session(session).header(csrf.getHeaderName(),csrf.getToken()))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(storage);
    }
}
