package com.trueket.backend.trueshot.service;

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
public class SupabaseTrueshotOriginalStore implements TrueshotOriginalStore {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String url;
    private final String serviceKey;
    private final String bucket;

    public SupabaseTrueshotOriginalStore(@Value("${storage.supabase-url:}") String url,
            @Value("${storage.service-role-key:}") String serviceKey,
            @Value("${storage.trueshot-original-bucket:}") String bucket) {
        this.url = url.replaceAll("/+$", "");
        this.serviceKey = serviceKey;
        this.bucket = bucket;
    }

    @Override
    public void upload(String key, byte[] bytes, String mimeType) {
        URI base;
        try { base = URI.create(url); } catch (IllegalArgumentException e) { throw unavailable(); }
        boolean local = Set.of("localhost", "127.0.0.1", "::1", "[::1]")
                .contains(String.valueOf(base.getHost()));
        if (serviceKey.isBlank() || !bucket.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
                || !key.matches("[A-Za-z0-9/_-]+\\.jpg") || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !("https".equals(base.getScheme()) || (local && "http".equals(base.getScheme())))) {
            throw unavailable();
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(url + "/storage/v1/object/" + bucket + "/" + key))
                    .timeout(Duration.ofSeconds(10))
                    .header("apikey", serviceKey)
                    .header("Authorization", "Bearer " + serviceKey)
                    .header("Content-Type", mimeType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200 && response.statusCode() != 201) throw unavailable();
        } catch (IOException | IllegalArgumentException e) {
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "사진을 저장하지 못했어요. 남은 시간 안에 다시 보내 주세요.");
    }
}
