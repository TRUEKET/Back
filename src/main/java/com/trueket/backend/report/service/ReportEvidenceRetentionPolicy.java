
package com.trueket.backend.report.service;

import java.time.OffsetDateTime;

public final class ReportEvidenceRetentionPolicy {

    private ReportEvidenceRetentionPolicy() {
    }

    // 신고에 연결된 최종 증빙 보관기간: 1년
    public static OffsetDateTime finalExpiresAt(
            OffsetDateTime createdAt
    ) {
        if (createdAt == null) {
            throw new IllegalArgumentException(
                    "증빙 생성 시각은 필수입니다."
            );
        }

        return createdAt.plusYears(1);
    }
}
