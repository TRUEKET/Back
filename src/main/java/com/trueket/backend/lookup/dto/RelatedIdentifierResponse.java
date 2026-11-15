
package com.trueket.backend.lookup.dto;

import com.trueket.backend.identifier.entity.enums.IdentifierType;

public record RelatedIdentifierResponse(
        Long identifierId,
        IdentifierType type,
        String maskedValue
) {
}
