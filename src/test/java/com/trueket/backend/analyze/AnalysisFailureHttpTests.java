package com.trueket.backend.analyze;

import com.trueket.backend.analyze.service.AnalysisSessionStore;
import com.trueket.backend.global.ai.AnalysisModelClient;
import com.trueket.backend.global.ai.ModelException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:failure-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "dispute.mail.enabled=false"})
@AutoConfigureMockMvc
class AnalysisFailureHttpTests {
    @Autowired MockMvc mvc;
    @Autowired AnalysisSessionStore store;
    @MockitoBean AnalysisModelClient model;
    private static final String READABLE = """
        {"readable":true,"detected_type":"transfer","type_mismatch":false,
         "sentences":[{"id":"s1","speaker":"SELLER","text":"공식 앱에서 거래해요","image_index":1}],"signals":[],
         "extracted":{"accounts":[],"phones":[],"x_accounts":[],"links":[]},
         "other_suspicious":null,"sensitive_values":[]}
        """;
    private record Browser(MockHttpSession session, CsrfToken csrf) {}
    private Browser browser() throws Exception {
        var result=mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
        return new Browser((MockHttpSession)result.getRequest().getSession(),
                (CsrfToken)result.getRequest().getAttribute(CsrfToken.class.getName()));
    }
    private MockMultipartFile image() throws Exception {
        var out=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(12,12,BufferedImage.TYPE_INT_RGB),"png",out);
        return new MockMultipartFile("captures","synthetic.png","image/png",out.toByteArray());
    }
    private ResultActions analyze(Browser b) throws Exception {
        return mvc.perform(multipart("/analyze").file(image()).session(b.session())
                .header(b.csrf().getHeaderName(),b.csrf().getToken()));
    }
    @ParameterizedTest @EnumSource(ModelException.Kind.class)
    void modelFailuresHaveStableHttpStatusNoVerdictAndBoundedRetries(ModelException.Kind kind) throws Exception {
        when(model.generate(anyString(),anyList(),any(),any())).thenThrow(new ModelException(kind));
        int expected=switch(kind) {
            case CONFIGURATION, AUTHENTICATION -> 503;
            case RATE_LIMIT -> 429;
            case TIMEOUT -> 504;
            default -> 502;
        };
        analyze(browser()).andExpect(status().is(expected))
                .andExpect(jsonPath("$.message").value("지금은 판독하지 못했어요. 잠시 후 다시 시도해 주세요"))
                .andExpect(jsonPath("$.assessment").doesNotExist()).andExpect(jsonPath("$.analyzeId").doesNotExist());
        verify(model,times(new ModelException(kind).retryable()?2:1)).generate(anyString(),anyList(),any(),any());
    }
    @Test void invalidUploadAndCsrfFailureNeverCallAi() throws Exception {
        var b=browser();
        mvc.perform(multipart("/analyze").file(image()).session(b.session()))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/analyze").session(b.session()).header(b.csrf().getHeaderName(),b.csrf().getToken()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/analyze").file(new MockMultipartFile("captures","fake.png","image/png",new byte[]{1,2}))
                .session(b.session()).header(b.csrf().getHeaderName(),b.csrf().getToken()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(model);
    }
    @Test void failedRetryKeepsPreviousResultButSuccessfulRetryReplacesIt() throws Exception {
        var b=browser();
        when(model.generate(anyString(),anyList(),any(),any())).thenReturn(READABLE);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        UUID first=UUID.fromString(json.readTree(analyze(b).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("analyzeId").asText());
        UUID owner=(UUID)b.session().getAttribute("analysisOwner");
        when(model.generate(anyString(),anyList(),any(),any())).thenThrow(new ModelException(ModelException.Kind.TIMEOUT));
        analyze(b).andExpect(status().isGatewayTimeout());
        assertNotNull(store.get(first,owner));
        doReturn("{\"readable\":false}").when(model).generate(anyString(),anyList(),any(),any());
        analyze(b).andExpect(status().isOk()).andExpect(jsonPath("$.readable").value(false))
                .andExpect(jsonPath("$.assessment").isEmpty()).andExpect(jsonPath("$.analyzeId").isEmpty());
        assertNotNull(store.get(first,owner));
        when(model.generate(anyString(),anyList(),any(),any())).thenReturn(READABLE);
        analyze(b).andExpect(status().isOk());
        var expired=assertThrows(com.trueket.backend.global.exception.ApiException.class,()->store.get(first,owner));
        assertEquals(404,expired.getStatus().value());
    }
}
