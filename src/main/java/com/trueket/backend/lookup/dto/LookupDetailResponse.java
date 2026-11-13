
package com.trueket.backend.lookup.dto;

import com.trueket.backend.identifier.entity.enums.IdentifierType;

import java.util.List;

public record LookupDetailResponse(
        Long identifierId,
        IdentifierType type,
        String maskedValue,
        String riskLevel,
        long fraudCount,
        long suspicionCount,
        List<RelatedIdentifierResponse> relatedIdentifiers,
        List<LookupTimelineResponse> timeline
) {
}
