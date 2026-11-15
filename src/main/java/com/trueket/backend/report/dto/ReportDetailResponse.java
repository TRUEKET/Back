package com.trueket.backend.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.trueket.backend.dispute.dto.DisputeNoticeResponse;
import com.trueket.backend.report.entity.Report;
import com.trueket.backend.report.entity.enums.ReportKind;
import com.trueket.backend.report.entity.enums.ReportStatus;
import com.trueket.backend.report.entity.enums.TradeType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public record ReportDetailResponse(
        Long reportId,
        ReportKind kind,
        ReportStatus status,
        TradeType tradeType,
        Object evidenceQuotes,
        Object referenceLinks,
        LocalDate sentDate,
        OffsetDateTime createdAt,
        List<ReportIdentifierResponse> identifiers,
        List<DisputeNoticeResponse> disputes
) {

    public static ReportDetailResponse from(
            Report report,
            List<ReportIdentifierResponse> identifiers,
            List<DisputeNoticeResponse> disputes
    ) {
        return new ReportDetailResponse(
                report.getId(),
                report.getKind(),
                report.getStatus(),
                report.getTradeType(),
                convertJsonNode(report.getEvidenceQuotes()),
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
    public static ReportDetailResponse from(
            Report report,
            List<ReportIdentifierResponse> identifiers
    ) {
        return from(report, identifiers, List.of());
    }
}