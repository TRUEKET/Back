
package com.trueket.backend.report.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SupabaseReportEvidenceStoreKeyTest {

    private SupabaseReportEvidenceStore store;

    @BeforeEach
    void setUp() {
        store = new SupabaseReportEvidenceStore(
                "https://example.supabase.co",
                "test-service-key",
                "report-evidence"
        );
    }

    @Test
    void 임시_증빙_경로가_정상적으로_생성된다() {
        String key = store.createPendingKey(15L);

        assertThat(key).matches(
                "pending/15/[0-9a-fA-F-]{36}\\.png"
        );
    }

    @Test
    void 호출할_때마다_다른_경로가_생성된다() {
        String first = store.createPendingKey(15L);
        String second = store.createPendingKey(15L);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 사용자_ID가_null이면_예외가_발생한다() {
        assertThatThrownBy(
                () -> store.createPendingKey(null)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 사용자_ID가_0이하이면_예외가_발생한다() {
        assertThatThrownBy(
                () -> store.createPendingKey(0L)
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(
                () -> store.createPendingKey(-1L)
        ).isInstanceOf(IllegalArgumentException.class);
    }


    @Test
    void 최종_증빙_경로는_임시_삭제를_허용하지_않는다() {
        assertThatThrownBy(() ->
                store.deletePending(
                        "reports/15/550e8400-e29b-41d4-a716-446655440000.png"
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 잘못된_임시_증빙_경로는_삭제를_거부한다() {
        assertThatThrownBy(() ->
                store.deletePending("../pending/test.png")
        ).isInstanceOf(IllegalArgumentException.class);
    }

}
