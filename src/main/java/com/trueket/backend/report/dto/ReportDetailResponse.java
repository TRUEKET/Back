
package com.trueket.backend.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.trueket.backend.dispute.dto.DisputeDetailResponse;
import com.trueket.backend.report.entity.Report;
import com.trueket.backend.report.entity.enums.ReportKind;
import com.trueket.backend.report.entity.enums.ReportStatus;
import com.trueket.backend.report.entity.enums.TradeType;
import com.trueket.backend.analyze.dto.ReportEvidenceDetail;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Schema(description = "RP-06 내 신고 상세 조회 응답")
public record ReportDetailResponse(

        @Schema(
                description = "신고 ID",
                example = "365"
        )
        Long reportId,

        @Schema(
                description = "신고 종류",
                example = "FRAUD"
        )
        ReportKind kind,

        @Schema(
                description = "신고 상태",
                example = "PUBLISHED"
        )
        ReportStatus status,

        @Schema(
                description = "JD 판독에서 결정된 거래 유형",
                example = "TRANSFER"
        )
        TradeType tradeType,

        @ArraySchema(
                arraySchema = @Schema(
                        description = "JD 판독에서 저장한 근거 문장 목록"
                ),
                schema = @Schema(
                        type = "string",
                        example = "본명 말고 닉네임으로 입금해주세요."
                )
        )
        Object evidenceQuotes,

        @ArraySchema(
                arraySchema = @Schema(
                        description = "주요 대화 상세 정보. 없으면 빈 배열"
                ),
                schema = @Schema(
                        implementation = ReportEvidenceDetail.class
                )
        )
        Object evidenceDetails,

        @ArraySchema(
                arraySchema = @Schema(
                        description = "신고 등록 시 입력한 참고 링크 목록"
                ),
                schema = @Schema(
                        type = "string",
                        format = "uri",
                        example = "https://example.com/reference"
                )
        )
        Object referenceLinks,

        @Schema(
                description = "송금 날짜",
                example = "2026-11-15",
                type = "string",
                format = "date",
                nullable = true
        )
        LocalDate sentDate,

        @Schema(
                description = "신고 등록 일시",
                example = "2026-11-15T20:00:00+09:00"
        )
        OffsetDateTime createdAt,

        @Schema(
                description = "본인 신고의 숨김 처리되지 않은 식별자 목록. 별표 없는 전체 표시는 각 항목의 value를 사용"
        )
        List<ReportIdentifierResponse> identifiers,

        // 상세 조회용 DTO로 변경
        @Schema(
                description = "해당 신고에 연결된 RP-08 이의제기 상세 목록"
        )
        List<DisputeDetailResponse> disputes

) {

    public static ReportDetailResponse from(
            Report report,
            List<ReportIdentifierResponse> identifiers,
            List<DisputeDetailResponse> disputes
    ) {
        return new ReportDetailResponse(
                report.getId(),
                report.getKind(),
                report.getStatus(),
                report.getTradeType(),
                convertJsonNode(report.getEvidenceQuotes()),

                // 신규 추가
                convertJsonNode(report.getEvidenceDetails()),

                convertJsonNode(report.getReferenceLinks()),
                report.getSentDate(),
                report.getCreatedAt(),
                List.copyOf(identifiers),
                List.copyOf(disputes)
        );
    }

    private static Object convertJsonNode(JsonNode node) {

        if (node == null || node.isNull()) {
            return List.of();
        }

        if (node.isArray()) {
            List<Object> result = new ArrayList<>();

            for (JsonNode item : node) {
                result.add(convertValue(item));
            }

            return result;
        }

        return convertValue(node);
    }

    private static Object convertValue(JsonNode node) {

        if (node.isTextual()) {
            return node.asText();
        }

        if (node.isBoolean()) {
            return node.asBoolean();
        }

        if (node.isNumber()) {
            return node.numberValue();
        }

        if (node.isNull()) {
            return null;
        }

        if (node.isArray()) {
            List<Object> result = new ArrayList<>();

            for (JsonNode item : node) {
                result.add(convertValue(item));
            }

            return result;
        }

        if (node.isObject()) {

            java.util.Map<String, Object> result =
                    new java.util.LinkedHashMap<>();

            node.fields().forEachRemaining(entry ->
                    result.put(
                            entry.getKey(),
                            convertValue(entry.getValue())
                    )
            );

            return result;
        }

        return node.asText();
    }

    // 기존 다른 코드와의 호환을 위해 유지
    public static ReportDetailResponse from(
            Report report,
            List<ReportIdentifierResponse> identifiers
    ) {
        return from(report, identifiers, List.of());
    }
}
