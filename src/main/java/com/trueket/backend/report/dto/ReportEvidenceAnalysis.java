
package com.trueket.backend.report.dto;

public record ReportEvidenceAnalysis(
        boolean transferCompleted,
        String bank,
        String accountNumber,
        BoundingBox accountBox,
        Double confidence
) {
    public record BoundingBox(
            double x1,
            double y1,
            double x2,
            double y2
    ) {
    }
}
