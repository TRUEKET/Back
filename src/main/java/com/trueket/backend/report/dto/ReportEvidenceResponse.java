
package com.trueket.backend.report.dto;

import java.util.UUID;

public record ReportEvidenceResponse(
        boolean verified,
        String message,
        UUID evidenceToken
) {
}
