package com.trueket.backend.lookup.dto;

import com.trueket.backend.identifier.entity.enums.IdentifierType;

public record LookupResponse(
        Long identifierId,
        IdentifierType type,
        String value,
        String riskLevel,
        long fraudCount,
        long suspicionCount
) {
}