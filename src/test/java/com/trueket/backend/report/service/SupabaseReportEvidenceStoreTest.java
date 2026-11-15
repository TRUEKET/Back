
package com.trueket.backend.report.service;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupabaseReportEvidenceStoreTest {

    private final HttpClient http = mock(HttpClient.class);

    private final SupabaseReportEvidenceStore storage =
            new SupabaseReportEvidenceStore(
                    "https://example.supabase.co",
                    "test-service-key",
                    "report-evidence",
                    http
            );

    @Test
    void 임시_증빙_경로를_생성한다() {
        String key = storage.createPendingKey(15L);

        assertThat(key).matches(
                "pending/15/[0-9a-fA-F-]{36}\\.png"
        );
    }

    @Test
    void 잘못된_사용자_ID는_거부한다() {
        assertThatThrownBy(() ->
                storage.createPendingKey(null)
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                storage.createPendingKey(0L)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 잘못된_임시_경로는_삭제하지_않는다() {
        assertThatThrownBy(() ->
                storage.deletePending(
                        "reports/15/test.png"
                )
        ).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(http);
    }

    @Test
    void 임시_증빙_업로드에_성공한다() throws Exception {
        HttpResponse<Void> response =
                mock(HttpResponse.class);

        when(response.statusCode()).thenReturn(201);

        when(http.send(
                any(),
                any(HttpResponse.BodyHandler.class)
        )).thenReturn(response);

        storage.upload(
                "pending/15/550e8400-e29b-41d4-a716-446655440000.png",
                new byte[]{1, 2, 3}
        );

        verify(http).send(
                any(),
                any(HttpResponse.BodyHandler.class)
        );
    }

    @Test
    void 업로드_실패_응답은_예외로_처리한다()
            throws Exception {

        HttpResponse<Void> response =
                mock(HttpResponse.class);

        when(response.statusCode()).thenReturn(500);

        when(http.send(
                any(),
                any(HttpResponse.BodyHandler.class)
        )).thenReturn(response);

        assertThatThrownBy(() ->
                storage.upload(
                        "pending/15/550e8400-e29b-41d4-a716-446655440000.png",
                        new byte[]{1, 2, 3}
                )
        ).isInstanceOf(RuntimeException.class);
    }

    @Test
    void 임시_증빙_삭제에_성공한다() throws Exception {
        HttpResponse<Void> response =
                mock(HttpResponse.class);

        when(response.statusCode()).thenReturn(200);

        when(http.send(
                any(),
                any(HttpResponse.BodyHandler.class)
        )).thenReturn(response);

        storage.deletePending(
                "pending/15/550e8400-e29b-41d4-a716-446655440000.png"
        );

        verify(http).send(
                any(),
                any(HttpResponse.BodyHandler.class)
        );
    }
}
