
package com.trueket.backend.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.global.ai.AnalysisModelClient;
import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.report.dto.ReportEvidenceAnalysis;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportEvidenceAiService {

    private final AnalysisModelClient modelClient;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String PROMPT = """
            당신은 송금 내역 이미지 분석기입니다.

            이미지에서 다음을 확인하세요.
            1. 송금 완료 또는 이체 완료 화면인지
            2. 수취 은행명
            3. 수취 계좌번호 전체
            4. 수취 계좌번호가 있는 이미지 영역

            주의:
            - 수취 계좌와 보내는 사람 계좌를 구분하세요.
            - 계좌번호가 불명확하면 추측하지 마세요.
            - 송금 완료 여부가 불명확하면 false로 반환하세요.
            - 좌표는 이미지 전체를 기준으로 0~1 사이 값입니다.
            - 이름, 잔액 등 다른 개인정보는 반환하지 마세요.
            - JSON 객체만 반환하세요.
            """;

    private static final String SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "transferCompleted": {
                  "type": "boolean"
                },
                "bank": {
                  "type": ["string", "null"]
                },
                "accountNumber": {
                  "type": ["string", "null"]
                },
                "accountBox": {
                  "type": ["object", "null"],
                  "properties": {
                    "x1": {"type": "number"},
                    "y1": {"type": "number"},
                    "x2": {"type": "number"},
                    "y2": {"type": "number"}
                  },
                  "required": ["x1", "y1", "x2", "y2"],
                  "additionalProperties": false
                },
                "confidence": {
                  "type": ["number", "null"]
                }
              },
              "required": [
                "transferCompleted",
                "bank",
                "accountNumber",
                "accountBox",
                "confidence"
              ],
              "additionalProperties": false
            }
            """;

    public ReportEvidenceAnalysis analyze(
            AnalysisModelClient.Image image
    ) {
        try {
            JsonNode schema = JSON.readTree(SCHEMA_JSON);

            String response = modelClient.generate(
                    PROMPT,
                    List.of(image),
                    schema,
                    Duration.ofSeconds(30)
            );

            ReportEvidenceAnalysis result = JSON.readValue(
                    response,
                    ReportEvidenceAnalysis.class
            );

            if (result == null) {
                throw invalid();
            }

            return result;

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    private ApiException invalid() {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "송금 내역을 확인하지 못했어요. 다른 사진으로 다시 시도해 주세요."
        );
    }
}
