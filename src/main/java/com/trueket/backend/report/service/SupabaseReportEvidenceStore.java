
package com.trueket.backend.report.service;

import com.trueket.backend.global.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

@Component
public class SupabaseReportEvidenceStore {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final String url;
    private final String serviceKey;
    private final String bucket;

    public SupabaseReportEvidenceStore(
            @Value("${storage.supabase-url:}") String url,
            @Value("${storage.service-role-key:}") String serviceKey,
            @Value("${storage.evidence-bucket:report-evidence}") String bucket
    ) {
        this.url = url.replaceAll("/+$", "");
        this.serviceKey = serviceKey;
        this.bucket = bucket;
    }

    public void upload(String key, byte[] bytes) {
        validateConfiguration(key, bytes);

        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(
                                    url + "/storage/v1/object/"
                                            + bucket + "/" + key
                            )
                    )
                    .timeout(Duration.ofSeconds(10))
                    .header("apikey", serviceKey)
                    .header("Authorization", "Bearer " + serviceKey)
                    .header("Content-Type", "image/png")
                    .header("x-upsert", "false")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();

            HttpResponse<Void> response = http.send(
                    request,
                    HttpResponse.BodyHandlers.discarding()
            );

            if (response.statusCode() != 200
                    && response.statusCode() != 201) {
                throw unavailable();
            }

        } catch (IOException | IllegalArgumentException e) {
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private void validateConfiguration(String key, byte[] bytes) {
        URI base;

        try {
            base = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw unavailable();
        }

        boolean local = Set.of(
                "localhost", "127.0.0.1", "::1", "[::1]"
        ).contains(String.valueOf(base.getHost()));

        if (serviceKey == null || serviceKey.isBlank()
                || bucket == null
                || !bucket.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
                || key == null
                || !key.matches(
                "reports/[1-9][0-9]*/"
                        + "[0-9a-fA-F-]{36}\\.png"
        )
                || bytes == null || bytes.length == 0
                || bytes.length > 5L * 1024 * 1024
                || base.getHost() == null
                || base.getUserInfo() != null
                || base.getQuery() != null
                || base.getFragment() != null
                || !("https".equals(base.getScheme())
                || (local && "http".equals(base.getScheme())))) {
            throw unavailable();
        }
    }

    private ApiException unavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "신고 증빙 사진을 저장하지 못했어요. 잠시 후 다시 시도해 주세요."
        );
    }
}
