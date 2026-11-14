
package com.trueket.backend.report.service;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;

class ReportEvidenceRetentionPolicyTest {

    @Test
    void 최종_증빙은_생성일로부터_1년_보관한다() {
        OffsetDateTime createdAt =
                OffsetDateTime.parse(
                        "2026-11-14T10:00:00+09:00"
                );

        OffsetDateTime expiresAt =
                ReportEvidenceRetentionPolicy.finalExpiresAt(
                        createdAt
                );

        assertThat(expiresAt).isEqualTo(
                OffsetDateTime.parse(
                        "2027-11-14T10:00:00+09:00"
                )
        );
    }

    @Test
    void 생성_시각이_없으면_예외가_발생한다() {
        assertThatThrownBy(() ->
                ReportEvidenceRetentionPolicy.finalExpiresAt(null)
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
