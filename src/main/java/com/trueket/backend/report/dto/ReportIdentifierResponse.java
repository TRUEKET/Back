package com.trueket.backend.report.dto;

import com.trueket.backend.identifier.entity.Identifier;

public record ReportIdentifierResponse(
        Long identifierId,
        String type,
        String maskedValue
) {
    public static ReportIdentifierResponse from(
            Identifier identifier
    ) {
        return new ReportIdentifierResponse(
                identifier.getId(),
                identifier.getType().name(),
                identifier.getValueMasked()
        );
    }
}