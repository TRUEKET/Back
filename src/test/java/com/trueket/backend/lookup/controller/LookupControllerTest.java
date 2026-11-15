
package com.trueket.backend.lookup.controller;

import com.trueket.backend.global.exception.GlobalExceptionHandler;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.lookup.dto.LookupResponse;
import com.trueket.backend.lookup.service.LookupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.trueket.backend.global.exception.BusinessException;
import com.trueket.backend.global.exception.ErrorCode;
import com.trueket.backend.lookup.dto.LookupDetailResponse;
import com.trueket.backend.lookup.dto.LookupTimelineResponse;
import com.trueket.backend.lookup.dto.RelatedIdentifierResponse;
import com.trueket.backend.report.entity.enums.ReportKind;
import com.trueket.backend.lookup.dto.LookupCompareResponse;
import com.trueket.backend.global.exception.BusinessException;
import com.trueket.backend.global.exception.ErrorCode;

import java.time.LocalDate;
import java.util.List;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;


class LookupControllerTest {

    private LookupService lookupService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        lookupService = mock(LookupService.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new LookupController(lookupService)
                )
                .setControllerAdvice(
                        new GlobalExceptionHandler()
                )
                .build();
    }

    @Test
    void 전화번호_조회_성공() throws Exception {
        // given
        LookupResponse response = new LookupResponse(
                1L,
                IdentifierType.PHONE,
                "01012345678",
                "DANGER",
                1,
                0
        );

        when(lookupService.lookup("010-1234-5678"))
                .thenReturn(response);

        // when & then
        mockMvc.perform(get("/lookup")
                        .param("q", "010-1234-5678"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code")
                        .value("SUCCESS"))
                .andExpect(jsonPath("$.data.identifierId")
                        .value(1))
                .andExpect(jsonPath("$.data.type")
                        .value("PHONE"))
                .andExpect(jsonPath("$.data.value")
                        .value("01012345678"))
                .andExpect(jsonPath("$.data.riskLevel")
                        .value("DANGER"))
                .andExpect(jsonPath("$.data.fraudCount")
                        .value(1));

        verify(lookupService)
                .lookup("010-1234-5678");
    }

    @Test
    void SNS_조회_성공() throws Exception {
        LookupResponse response = new LookupResponse(
                2L,
                IdentifierType.SNS,
                "trueket_user",
                "SUSPICION",
                0,
                2
        );

        when(lookupService.lookup("@Trueket_User"))
                .thenReturn(response);

        mockMvc.perform(get("/lookup")
                        .param("q", "@Trueket_User"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type")
                        .value("SNS"))
                .andExpect(jsonPath("$.data.riskLevel")
                        .value("SUSPICION"))
                .andExpect(jsonPath("$.data.suspicionCount")
                        .value(2));
    }

    @Test
    void 계좌번호_조회_성공() throws Exception {
        LookupResponse response = new LookupResponse(
                3L,
                IdentifierType.ACCOUNT,
                "KB:123456789012",
                "SAFE",
                0,
                0
        );

        when(lookupService.lookup("KB 123-456-789012"))
                .thenReturn(response);

        mockMvc.perform(get("/lookup")
                        .param("q", "KB 123-456-789012"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type")
                        .value("ACCOUNT"))
                .andExpect(jsonPath("$.data.riskLevel")
                        .value("SAFE"));
    }

    @Test
    void 검색_결과가_없으면_SAFE() throws Exception {
        LookupResponse response = new LookupResponse(
                null,
                IdentifierType.PHONE,
                null,
                "SAFE",
                0,
                0
        );

        when(lookupService.lookup("01099998888"))
                .thenReturn(response);

        mockMvc.perform(get("/lookup")
                        .param("q", "01099998888"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identifierId")
                        .doesNotExist())
                .andExpect(jsonPath("$.data.riskLevel")
                        .value("SAFE"))
                .andExpect(jsonPath("$.data.fraudCount")
                        .value(0))
                .andExpect(jsonPath("$.data.suspicionCount")
                        .value(0));
    }

    @Test
    void 필수_파라미터_누락시_400() throws Exception {
        mockMvc.perform(get("/lookup"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_REQUEST"));

        verifyNoInteractions(lookupService);
    }

    @Test
    void 잘못된_입력값이면_400() throws Exception {
        when(lookupService.lookup(anyString()))
                .thenThrow(new IllegalArgumentException(
                        "잘못된 입력값"
                ));

        mockMvc.perform(get("/lookup")
                        .param("q", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_REQUEST"));
    }

    @Test
    void 서버_예외가_발생하면_500() throws Exception {
        when(lookupService.lookup(anyString()))
                .thenThrow(new RuntimeException(
                        "테스트용 서버 오류"
                ));

        mockMvc.perform(get("/lookup")
                        .param("q", "01012345678"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.data")
                        .doesNotExist());
    }


    @Test
    void 상세조회_성공시_200과_공통응답을_반환한다() throws Exception {
        LookupDetailResponse response = new LookupDetailResponse(
                1L,
                IdentifierType.PHONE,
                "010-****-5678",
                "DANGER",
                1,
                1,
                List.of(
                        new RelatedIdentifierResponse(
                                2L,
                                IdentifierType.ACCOUNT,
                                "****1234"
                        )
                ),
                List.of(
                        new LookupTimelineResponse(
                                LocalDate.of(2026, 11, 11),
                                ReportKind.FRAUD
                        ),
                        new LookupTimelineResponse(
                                LocalDate.of(2026, 11, 10),
                                ReportKind.SUSPICION
                        )
                )
        );

        when(lookupService.getLookupDetail(1L))
                .thenReturn(response);

        mockMvc.perform(get("/lookup/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.identifierId").value(1))
                .andExpect(jsonPath("$.data.type").value("PHONE"))
                .andExpect(jsonPath("$.data.maskedValue")
                        .value("010-****-5678"))
                .andExpect(jsonPath("$.data.riskLevel").value("DANGER"))
                .andExpect(jsonPath("$.data.fraudCount").value(1))
                .andExpect(jsonPath("$.data.suspicionCount").value(1))
                .andExpect(jsonPath("$.data.relatedIdentifiers.length()")
                        .value(1))
                .andExpect(jsonPath("$.data.relatedIdentifiers[0].identifierId")
                        .value(2))
                .andExpect(jsonPath("$.data.relatedIdentifiers[0].maskedValue")
                        .value("****1234"))
                .andExpect(jsonPath("$.data.timeline.length()").value(2))
                .andExpect(jsonPath("$.data.timeline[0].kind")
                        .value("FRAUD"))
                .andExpect(jsonPath("$.data.timeline[1].kind")
                        .value("SUSPICION"));

        verify(lookupService).getLookupDetail(1L);
    }


    @Test
    void 상세조회_존재하지않는_식별자는_404() throws Exception {
        when(lookupService.getLookupDetail(999L))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.IDENTIFIER_NOT_FOUND
                        )
                );

        mockMvc.perform(get("/lookup/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("IDENTIFIER_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(lookupService).getLookupDetail(999L);
    }


    @Test
    void 상세조회_숫자가아닌_ID는_400() throws Exception {
        mockMvc.perform(get("/lookup/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_REQUEST"));

        verifyNoInteractions(lookupService);
    }


    @Test
    void RP03_비교_일치시_200() throws Exception {
        when(lookupService.compare(1L, "010-1234-5678"))
                .thenReturn(new LookupCompareResponse(
                        true,
                        "01012345678"
                ));

        mockMvc.perform(post("/lookup/1/compare")
                        .contentType("application/json")
                        .content("""
                            {
                              "value": "010-1234-5678"
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.matched").value(true))
                .andExpect(jsonPath("$.data.value")
                        .value("01012345678"));

        verify(lookupService)
                .compare(1L, "010-1234-5678");
    }

    @Test
    void RP03_비교_불일치시_전체값을_반환하지_않는다()
            throws Exception {

        when(lookupService.compare(1L, "010-9999-8888"))
                .thenReturn(new LookupCompareResponse(false, null));

        mockMvc.perform(post("/lookup/1/compare")
                        .contentType("application/json")
                        .content("""
                            {
                              "value": "010-9999-8888"
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matched").value(false))
                .andExpect(jsonPath("$.data.value").doesNotExist());
    }

    @Test
    void RP03_존재하지않는_식별자는_404() throws Exception {
        when(lookupService.compare(999L, "01012345678"))
                .thenThrow(new BusinessException(
                        ErrorCode.IDENTIFIER_NOT_FOUND
                ));

        mockMvc.perform(post("/lookup/999/compare")
                        .contentType("application/json")
                        .content("""
                            {
                              "value": "01012345678"
                            }
                            """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("IDENTIFIER_NOT_FOUND"));
    }

    @Test
    void RP03_빈_입력값은_400() throws Exception {
        mockMvc.perform(post("/lookup/1/compare")
                        .contentType("application/json")
                        .content("""
                            {
                              "value": ""
                            }
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_REQUEST"));

        verifyNoInteractions(lookupService);
    }

    @Test
    void RP03_요청본문이_없으면_400() throws Exception {
        mockMvc.perform(post("/lookup/1/compare")
                        .contentType("application/json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_REQUEST"));

        verifyNoInteractions(lookupService);
    }

    @Test
    void RP03_잘못된_JSON은_400() throws Exception {
        mockMvc.perform(post("/lookup/1/compare")
                        .contentType("application/json")
                        .content("{invalid-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_REQUEST"));

        verifyNoInteractions(lookupService);
    }

}
