
package com.trueket.backend.report.service;

import com.trueket.backend.global.exception.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.UUID;

@Component
public class SupabaseReportEvidenceStore {

    private final HttpClient http;

    private final String url;
    private final String serviceKey;
    private final String bucket;

    @Autowired
    public SupabaseReportEvidenceStore(
            @Value("${storage.supabase-url:}") String url,
            @Value("${storage.service-role-key:}") String serviceKey,
            @Value("${storage.evidence-bucket:report-evidence}") String bucket
    ) {
        this(
                url,
                serviceKey,
                bucket,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build()
        );
    }

    // 단위 테스트에서 HttpClient를 주입하기 위한 생성자
    SupabaseReportEvidenceStore(
            String url,
            String serviceKey,
            String bucket,
            HttpClient http
    ) {
        this.url = url.replaceAll("/+$", "");
        this.serviceKey = serviceKey;
        this.bucket = bucket;
        this.http = java.util.Objects.requireNonNull(http);
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
                "(?:reports|pending)/[1-9][0-9]*/"
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


    public String createPendingKey(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException(
                    "올바르지 않은 사용자 ID입니다."
            );
        }

        return "pending/"
                + userId
                + "/"
                + UUID.randomUUID()
                + ".png";
    }


    public void deletePending(String key) {
        if (key == null || !key.matches(
                "pending/[1-9][0-9]*/"
                        + "[0-9a-fA-F-]{36}\\.png"
        )) {
            throw new IllegalArgumentException(
                    "올바르지 않은 임시 증빙 경로입니다."
            );
        }

        // 기존 설정 검증
        validateConfiguration(key, new byte[]{1});

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
                    .DELETE()
                    .build();

            HttpResponse<Void> response = http.send(
                    request,
                    HttpResponse.BodyHandlers.discarding()
            );

            if (response.statusCode() != 200
                    && response.statusCode() != 204) {
                throw unavailable();
            }

        } catch (IOException | IllegalArgumentException e) {
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }


    public String createReportKey(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException(
                    "올바르지 않은 사용자 ID입니다."
            );
        }

        return "reports/"
                + userId
                + "/"
                + UUID.randomUUID()
                + ".png";
    }

    public void copyPendingToReport(
            String sourceKey,
            String targetKey
    ) {
        validateStorageKey(sourceKey, "pending");
        validateStorageKey(targetKey, "reports");

        // 다른 사용자 경로로 복사하지 못하도록 검증
        String sourceUserId = sourceKey.split("/")[1];
        String targetUserId = targetKey.split("/")[1];

        if (!sourceUserId.equals(targetUserId)) {
            throw new IllegalArgumentException(
                    "증빙 파일 소유자가 일치하지 않습니다."
            );
        }

        validateConfiguration(sourceKey, new byte[]{1});

        String body = """
        {
          "sourceBucket": "%s",
          "sourceKey": "%s",
          "destinationBucket": "%s",
          "destinationKey": "%s"
        }
        """.formatted(
                bucket,
                sourceKey,
                bucket,
                targetKey
        );

        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(
                                    url + "/storage/v1/object/copy"
                            )
                    )
                    .timeout(Duration.ofSeconds(10))
                    .header("apikey", serviceKey)
                    .header("Authorization", "Bearer " + serviceKey)
                    .header("Content-Type", "application/json")
                    .POST(
                            HttpRequest.BodyPublishers.ofString(body)
                    )
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

    public void deleteReport(String key) {
        validateStorageKey(key, "reports");
        validateConfiguration(key, new byte[]{1});

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
                    .DELETE()
                    .build();

            HttpResponse<Void> response = http.send(
                    request,
                    HttpResponse.BodyHandlers.discarding()
            );

            if (response.statusCode() != 200
                    && response.statusCode() != 204) {
                throw unavailable();
            }

        } catch (IOException | IllegalArgumentException e) {
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private void validateStorageKey(
            String key,
            String expectedPrefix
    ) {
        if (key == null || !key.matches(
                expectedPrefix
                        + "/[1-9][0-9]*/"
                        + "[0-9a-fA-F-]{36}\\.png"
        )) {
            throw new IllegalArgumentException(
                    "올바르지 않은 증빙 파일 경로입니다."
            );
        }
    }


}
