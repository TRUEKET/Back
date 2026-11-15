
package com.trueket.backend.lookup.dto;

public record LookupCompareResponse(
        boolean matched,
        String value
) {
}
