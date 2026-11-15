
package com.trueket.backend.lookup.dto;

import jakarta.validation.constraints.NotBlank;

public record LookupCompareRequest(
        @NotBlank(message = "비교할 값을 입력해 주세요.")
        String value
) {
}
