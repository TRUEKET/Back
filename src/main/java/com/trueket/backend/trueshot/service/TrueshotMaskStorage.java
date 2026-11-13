package com.trueket.backend.trueshot.service;

import com.trueket.backend.global.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.io.IOException;

@Component
public class TrueshotMaskStorage {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String url, key, originalBucket, maskedBucket;
    public TrueshotMaskStorage(@Value("${storage.supabase-url:}") String url,
            @Value("${storage.service-role-key:}") String key,
            @Value("${storage.trueshot-original-bucket:}") String originalBucket,
            @Value("${storage.trueshot-masked-bucket:}") String maskedBucket) {
        this.url=url.replaceAll("/+$", ""); this.key=key;
        this.originalBucket=originalBucket; this.maskedBucket=maskedBucket;
    }
    public byte[] original(String path) { return download(originalBucket, path); }
    public byte[] masked(String path) { return download(maskedBucket, path); }
    private byte[] download(String bucket, String path) {
        var request = request(bucket, path, true).GET().build();
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try (var body=response.body()) {
                if (response.statusCode()!=200) throw unavailable();
                byte[] bytes=body.readNBytes(6_000_001);
                if (bytes.length==0 || bytes.length>6_000_000) throw unavailable();
                return bytes;
            }
        } catch (IOException e) { throw unavailable(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw unavailable(); }
    }
    public void saveMasked(String path, byte[] bytes) {
        var request=request(maskedBucket,path,false).header("Content-Type","image/jpeg")
                .header("x-upsert","true").POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.discarding());
            if (response.statusCode()!=200 && response.statusCode()!=201) throw unavailable();
        } catch (IOException e) { throw unavailable(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw unavailable(); }
    }
    private HttpRequest.Builder request(String bucket,String path,boolean download) {
        try {
            URI base=URI.create(url);
            boolean local=java.util.Set.of("localhost","127.0.0.1","[::1]").contains(String.valueOf(base.getHost()));
            if (key.isBlank() || base.getHost()==null || base.getUserInfo()!=null || base.getQuery()!=null
                    || base.getFragment()!=null || !("https".equals(base.getScheme()) || (local && "http".equals(base.getScheme())))
                    || !bucket.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
                    || path==null || !path.matches("trueshots/[0-9]+/[A-Za-z0-9-]+\\.jpg")) throw unavailable();
            return HttpRequest.newBuilder(URI.create(url+"/storage/v1/object/"+(download?"authenticated/":"")+bucket+"/"+path))
                    .timeout(Duration.ofSeconds(10)).header("apikey",key).header("Authorization","Bearer "+key);
        } catch (IllegalArgumentException e) { throw unavailable(); }
    }
    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"가린 사진을 저장하거나 불러오지 못했어요.");
    }
}
