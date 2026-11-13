package com.trueket.backend.global.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.global.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Component
public class SupabaseStorageDeletion {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String url;
    private final String serviceKey;
    private final Map<StorageArea,String> buckets;

    public SupabaseStorageDeletion(@Value("${storage.supabase-url:}") String url,
                                   @Value("${storage.service-role-key:}") String serviceKey,
                                   @Value("${storage.evidence-bucket:report-evidence}") String evidence,
                                   @Value("${storage.trueshot-masked-bucket:trueshot-photos}") String masked,
                                   @Value("${storage.trueshot-original-bucket:trueshot-photos}") String original) {
        this.url = url.replaceAll("/+$", "");
        this.serviceKey = serviceKey;
        this.buckets = Map.of(StorageArea.EVIDENCE,evidence,StorageArea.TRUESHOT_MASKED,masked,
                StorageArea.TRUESHOT_ORIGINAL,original);
    }

    public void deleteAll(List<StorageTarget> targets) {
        if (targets.isEmpty()) return;
        Map<String,Set<String>> grouped = new LinkedHashMap<>();
        // Validate every destination before making any irreversible storage request.
        for (var target : targets) {
            String bucket = buckets.get(target.area());
            if (bucket == null || !bucket.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}") || !validKey(target.key())) {
                throw unavailable();
            }
            grouped.computeIfAbsent(bucket, ignored -> new LinkedHashSet<>()).add(target.key());
        }
        URI base;
        try { base = URI.create(url); } catch (IllegalArgumentException e) { throw unavailable(); }
        boolean local = Set.of("localhost","127.0.0.1","::1","[::1]").contains(String.valueOf(base.getHost()));
        if (serviceKey.isBlank() || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null
                || !("https".equals(base.getScheme()) || (local && "http".equals(base.getScheme())))) {
            throw unavailable();
        }
        for (var group : grouped.entrySet()) {
            List<String> keys = new ArrayList<>(group.getValue());
            for (int offset=0;offset<keys.size();offset+=100) {
                deleteBatch(group.getKey(), keys.subList(offset,Math.min(offset+100,keys.size())));
            }
        }
    }

    private void deleteBatch(String bucket, List<String> keys) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url+"/storage/v1/object/"+bucket))
                    .timeout(Duration.ofSeconds(5)).header("apikey",serviceKey)
                    .header("Authorization","Bearer "+serviceKey).header("Content-Type","application/json")
                    .method("DELETE",HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("prefixes",keys))))
                    .build();
            var response = http.send(request,HttpResponse.BodyHandlers.ofString());
            // Missing objects are handled idempotently by Supabase's bulk removal API (200 + []).
            // A missing bucket, auth failure or server failure is not a successful deletion.
            if (response.statusCode()!=200 || response.body().length()>1_000_000) throw unavailable();
            var result = json.readTree(response.body());
            if (result == null || !result.isArray()) throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | IllegalArgumentException e) {
            throw unavailable();
        }
    }

    private boolean validKey(String key) {
        if (key == null || key.isBlank() || key.length()>1024 || key.startsWith("/")
                || key.contains(":") || key.contains("\\") || key.contains("%")
                || key.contains("?") || key.contains("#") || key.contains("*")
                || key.chars().anyMatch(Character::isISOControl)) return false;
        return Arrays.stream(key.split("/",-1)).noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."));
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "사진 삭제를 완료하지 못했어요. 계정은 삭제하지 않았으니 잠시 후 다시 시도해 주세요");
    }
}