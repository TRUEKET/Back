
package com.trueket.backend.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.trueket.backend.global.ai.AnalysisModelClient;
import com.trueket.backend.global.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportEvidencePrivacyValidatorTest {

    private AnalysisModelClient modelClient;
    private ReportEvidencePrivacyValidator validator;

    private final byte[] validImage = new byte[]{1, 2, 3};

    @BeforeEach
    void setUp() {
        modelClient = mock(AnalysisModelClient.class);
        validator = new ReportEvidencePrivacyValidator(modelClient);
    }

    @Test
    void 계좌번호만_보이면_검증에_성공한다() {
        mockResponse("""
                {
                  "accountVisible": true,
                  "containsName": false,
                  "containsOtherAccount": false,
                  "containsBalance": false,
                  "containsOtherPersonalInfo": false,
                  "safe": true
                }
                """);

        assertThatCode(() -> validator.validate(validImage))
                .doesNotThrowAnyException();

        verify(modelClient).generate(
                anyString(),
                anyList(),
                any(JsonNode.class),
                any(Duration.class)
        );
    }

    @Test
    void 이름이_포함되면_거부한다() {
        mockResponse(response(true, true, false, false, false, false));

        assertRejected();
    }

    @Test
    void 다른_계좌번호가_포함되면_거부한다() {
        mockResponse(response(true, false, true, false, false, false));

        assertRejected();
    }

    @Test
    void 잔액이_포함되면_거부한다() {
        mockResponse(response(true, false, false, true, false, false));

        assertRejected();
    }

    @Test
    void 기타_개인정보가_포함되면_거부한다() {
        mockResponse(response(true, false, false, false, true, false));

        assertRejected();
    }

    @Test
    void 계좌번호가_보이지_않으면_거부한다() {
        mockResponse(response(false, false, false, false, false, false));

        assertRejected();
    }

    @Test
    void AI가_안전하지_않다고_판단하면_거부한다() {
        mockResponse(response(true, false, false, false, false, false));

        assertRejected();
    }

    @Test
    void AI_응답이_잘못되거나_이미지가_없으면_거부한다() {
        mockResponse("invalid json");

        assertRejected();

        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> validator.validate(new byte[0]))
                .isInstanceOf(ApiException.class);
    }

    private void mockResponse(String response) {
        when(modelClient.generate(
                anyString(),
                anyList(),
                any(JsonNode.class),
                any(Duration.class)
        )).thenReturn(response);
    }

    private void assertRejected() {
        assertThatThrownBy(() -> validator.validate(validImage))
                .isInstanceOf(ApiException.class);
    }

    private String response(
            boolean accountVisible,
            boolean containsName,
            boolean containsOtherAccount,
            boolean containsBalance,
            boolean containsOtherPersonalInfo,
            boolean safe
    ) {
        return """
                {
                  "accountVisible": %s,
                  "containsName": %s,
                  "containsOtherAccount": %s,
                  "containsBalance": %s,
                  "containsOtherPersonalInfo": %s,
                  "safe": %s
                }
                """.formatted(
                accountVisible,
                containsName,
                containsOtherAccount,
                containsBalance,
                containsOtherPersonalInfo,
                safe
        );
    }


    @Test
    void 필수_필드가_누락되면_거부한다() {
        mockResponse("""
            {
              "accountVisible": true,
              "containsName": false,
              "containsOtherAccount": false,
              "containsBalance": false,
              "safe": true
            }
            """);

        assertRejected();
    }

    @Test
    void boolean_필드가_문자열이면_거부한다() {
        mockResponse("""
            {
              "accountVisible": true,
              "containsName": false,
              "containsOtherAccount": false,
              "containsBalance": false,
              "containsOtherPersonalInfo": false,
              "safe": "true"
            }
            """);

        assertRejected();
    }

    @Test
    void AI_호출에서_예외가_발생하면_검증에_실패한다() {
        when(modelClient.generate(
                anyString(),
                anyList(),
                any(JsonNode.class),
                any(Duration.class)
        )).thenThrow(new RuntimeException("AI failure"));

        assertRejected();
    }

}
