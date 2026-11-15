
package com.trueket.backend.dispute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.dispute.dto.DisputeAiAnalysis;
import com.trueket.backend.global.ai.AnalysisModelClient;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DisputeAiService {

    private final AnalysisModelClient modelClient;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TRANSFER_PROMPT = """
            당신은 트루켓의 은행 이체 증빙 분석기입니다.

            이미지에 적힌 사실만 추출하세요.

            반드시 '보내는 계좌'와 '받는 계좌'를 구분하세요.

            senderBank:
            - 돈을 보낸 계좌의 은행명

            senderAccount:
            - 돈을 보낸 계좌의 전체 계좌번호

            receiverBank:
            - 돈을 받은 계좌의 은행명

            receiverAccount:
            - 돈을 받은 계좌의 전체 계좌번호

            예시:
            '보내는 계좌: 신한 110-222-3-456789'
            '받는 계좌: 신한 111-111-1-111111'

            이 경우:
            senderBank = "신한"
            senderAccount = "110-222-3-456789"
            receiverBank = "신한"
            receiverAccount = "111-111-1-111111"

            이미지에 표시된 이체 방향을 기준으로만 판단하세요.
            돈을 보낸 사람이 이미지를 업로드했다는 이유로
            받는 계좌를 보내는 계좌로 분류하면 안 됩니다.

            docType:
            - transfer_receipt: 이체 내역 또는 이체 완료 증빙
            - other: 다른 문서
            - unknown: 판독할 수 없는 문서

            completed:
            - 이체 완료가 명확하게 표시되어 있으면 true
            - 불명확하면 false

            readable:
            - 이미지의 주요 내용을 판독할 수 있으면 true

            delivered:
            - 이 분석에서는 항상 false

            개인정보 마스킹:
            - 계좌 소유자 이름, 잔액, 불필요한 다른 거래 내용,
              비대상 계좌번호 등 개인정보를 가릴 좌표를 반환하세요.
            - 검증 대상 계좌는 반드시 정확히 추출하세요.
            - 마스킹 좌표는 이미지 너비와 높이를 기준으로
              0~1 사이의 정규화 좌표로 반환하세요.

            금지:
            - 보이지 않는 계좌번호를 추측하지 마세요.
            - 일부만 가려진 계좌번호를 복원하지 마세요.
            - 두 계좌의 역할을 임의로 뒤집지 마세요.
            - 실제 은행 거래가 발생했다고 보증하지 마세요.

            JSON 객체만 반환하세요.
            """;

    private static final String DELIVERY_PROMPT = """
            당신은 트루켓의 표 전달 내역 분석기입니다.

            제출된 이미지에서 판매자가 구매자에게
            표 또는 티켓을 전달했다는 명시적인 기록이
            있는지 분석하세요.

            확인할 내용:
            1. 이미지가 읽을 수 있는지
            2. 표, 티켓, 예매권 등의 전달과 관련 있는지
            3. 구매자에게 전달 완료되었다는 근거가 있는지
            4. 개인정보를 가릴 좌표

            docType:
            - ticket_delivery: 표 전달 기록 또는 관련 대화
            - other: 다른 문서
            - unknown: 판독 불가

            delivered:
            - '전달 완료', '티켓을 보냈습니다'처럼
              전달 사실이 명시적으로 확인되면 true
            - 단순히 표 사진만 있거나 전달 여부가 불분명하면 false

            readable:
            - 주요 문구를 읽을 수 있으면 true

            completed:
            - 이 분석에서는 항상 false

            senderBank, senderAccount,
            receiverBank, receiverAccount:
            - 이 분석에서는 모두 null

            개인정보 마스킹:
            - 전화번호, 이메일, 프로필 이름, 대화 상대 이름,
              불필요한 개인정보를 가릴 좌표를 반환하세요.
            - 표 전달 여부를 판단하는 데 필요한 문장은
              가능한 한 유지하세요.

            주의:
            - 표가 실제로 전달되었는지 외부에서 검증할 수 없습니다.
            - 이미지에서 확인되는 내용만 판단하세요.
            - 전달 사실을 추측하지 마세요.
            - 마스킹 좌표는 0~1 사이입니다.
            - JSON 객체만 반환하세요.
            """;

    private static final String SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "docType": {
                  "type": "string"
                },
                "senderBank": {
                  "type": ["string", "null"]
                },
                "senderAccount": {
                  "type": ["string", "null"]
                },
                "receiverBank": {
                  "type": ["string", "null"]
                },
                "receiverAccount": {
                  "type": ["string", "null"]
                },
                "completed": {
                  "type": "boolean"
                },
                "readable": {
                  "type": "boolean"
                },
                "delivered": {
                  "type": "boolean"
                },
                "maskBoxes": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "x1": {"type": "number"},
                      "y1": {"type": "number"},
                      "x2": {"type": "number"},
                      "y2": {"type": "number"}
                    },
                    "required": [
                      "x1", "y1", "x2", "y2"
                    ],
                    "additionalProperties": false
                  }
                }
              },
              "required": [
                "docType",
                "senderBank",
                "senderAccount",
                "receiverBank",
                "receiverAccount",
                "completed",
                "readable",
                "delivered",
                "maskBoxes"
              ],
              "additionalProperties": false
            }
            """;

    public DisputeAiAnalysis analyzeTransfer(
            AnalysisModelClient.Image image
    ) {
        return analyze(TRANSFER_PROMPT, image);
    }

    public DisputeAiAnalysis analyzeDelivery(
            AnalysisModelClient.Image image
    ) {
        return analyze(DELIVERY_PROMPT, image);
    }

    private DisputeAiAnalysis analyze(
            String prompt,
            AnalysisModelClient.Image image
    ) {
        try {
            JsonNode schema = JSON.readTree(SCHEMA_JSON);

            String response = modelClient.generate(
                    prompt,
                    List.of(image),
                    schema,
                    Duration.ofSeconds(30)
            );

            DisputeAiAnalysis result = JSON.readValue(
                    response,
                    DisputeAiAnalysis.class
            );

            if (result == null) {
                throw new IllegalStateException(
                        "AI-04 응답이 비어 있습니다."
                );
            }

            return result;

        } catch (Exception e) {
            throw new IllegalStateException(
                    "AI-04 증빙 분석을 완료하지 못했습니다.",
                    e
            );
        }
    }
}
