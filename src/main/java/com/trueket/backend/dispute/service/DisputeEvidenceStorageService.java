
package com.trueket.backend.dispute.service;

import com.trueket.backend.dispute.dto.DisputeAiAnalysis;
import com.trueket.backend.global.ai.AnalysisModelClient;
import com.trueket.backend.global.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class DisputeEvidenceStorageService {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final String url;
    private final String serviceKey;

    private static final String BUCKET = "dispute-evidence";

    public DisputeEvidenceStorageService(
            @Value("${storage.supabase-url:}") String url,
            @Value("${storage.service-role-key:}") String serviceKey
    ) {
        this.url = url.replaceAll("/+$", "");
        this.serviceKey = serviceKey;
    }

    // 이미지 검사 후 AI가 읽을 PNG로 변환
    public byte[] prepare(MultipartFile file) {
        if (file == null || file.isEmpty()
                || file.getSize() > 5L * 1024 * 1024) {
            throw badRequest("5MB 이하의 증빙 사진이 필요합니다.");
        }

        if (!List.of("image/png", "image/jpeg")
                .contains(file.getContentType())) {
            throw badRequest("PNG 또는 JPEG 이미지만 업로드할 수 있습니다.");
        }

        try {
            BufferedImage image = ImageIO.read(file.getInputStream());

            if (image == null
                    || image.getWidth() <= 0
                    || image.getHeight() <= 0
                    || (long) image.getWidth() * image.getHeight()
                    > 20_000_000L) {
                throw badRequest("올바른 이미지가 아닙니다.");
            }

            BufferedImage rgb = new BufferedImage(
                    image.getWidth(),
                    image.getHeight(),
                    BufferedImage.TYPE_INT_RGB
            );

            Graphics2D g = rgb.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(rgb, "png", output);
            return output.toByteArray();

        } catch (IOException e) {
            throw badRequest("증빙 이미지를 읽지 못했습니다.");
        }
    }

    public AnalysisModelClient.Image toAiImage(byte[] png) {
        return new AnalysisModelClient.Image(
                "image/png",
                Base64.getEncoder().encodeToString(png)
        );
    }

    public byte[] mask(
            byte[] originalPng,
            DisputeAiAnalysis analysis
    ) {
        try {
            BufferedImage image = ImageIO.read(
                    new ByteArrayInputStream(originalPng)
            );

            if (image == null || analysis.maskBoxes() == null) {
                throw badRequest("개인정보 마스킹 결과가 없습니다.");
            }

            Graphics2D graphics = image.createGraphics();

            try {
                graphics.setColor(Color.BLACK);

                int width = image.getWidth();
                int height = image.getHeight();

                for (DisputeAiAnalysis.MaskBox box
                        : analysis.maskBoxes()) {

                    if (box == null
                            || !Double.isFinite(box.x1())
                            || !Double.isFinite(box.y1())
                            || !Double.isFinite(box.x2())
                            || !Double.isFinite(box.y2())
                            || box.x1() < 0 || box.y1() < 0
                            || box.x2() > 1 || box.y2() > 1
                            || box.x1() >= box.x2()
                            || box.y1() >= box.y2()) {
                        throw badRequest("마스킹 좌표가 올바르지 않습니다.");
                    }

                    int x1 = (int) Math.floor(box.x1() * width);
                    int y1 = (int) Math.floor(box.y1() * height);
                    int x2 = (int) Math.ceil(box.x2() * width);
                    int y2 = (int) Math.ceil(box.y2() * height);

                    graphics.fillRect(
                            x1, y1, x2 - x1, y2 - y1
                    );
                }
            } finally {
                graphics.dispose();
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);

            byte[] masked = output.toByteArray();

            if (masked.length > 5L * 1024 * 1024) {
                throw badRequest("마스킹 이미지 크기가 5MB를 초과합니다.");
            }

            return masked;

        } catch (IOException e) {
            throw badRequest("이미지 마스킹에 실패했습니다.");
        }
    }

    public String upload(Long disputeId, byte[] png) {
        if (disputeId == null || disputeId <= 0) {
            throw badRequest("올바르지 않은 접수 ID입니다.");
        }

        String key = "disputes/" + disputeId
                + "/" + UUID.randomUUID() + ".png";

        send("POST", key, png);
        return key;
    }

    public void delete(String key) {
        send("DELETE", key, null);
    }

    private void send(String method, String key, byte[] bytes) {
        if (serviceKey == null || serviceKey.isBlank()
                || url.isBlank()
                || !url.startsWith("https://")
                || !key.matches(
                "disputes/[1-9][0-9]*/[0-9a-fA-F-]{36}\\.png"
        )) {
            throw unavailable();
        }

        try {
            URI uri = URI.create(
                    url + "/storage/v1/object/" + BUCKET + "/" + key
            );

            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("apikey", serviceKey)
                    .header("Authorization", "Bearer " + serviceKey);

            if ("POST".equals(method)) {
                builder.header("Content-Type", "image/png")
                        .header("x-upsert", "false")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
            } else {
                builder.DELETE();
            }

            HttpResponse<String> response = http.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            int code = response.statusCode();

            if (code < 200 || code >= 300) {
                String errorCode = "UNKNOWN";

                try {
                    var errorJson = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(response.body());

                    if (errorJson.hasNonNull("code")) {
                        errorCode = errorJson.get("code").asText();
                    } else if (errorJson.hasNonNull("error")) {
                        errorCode = errorJson.get("error").asText();
                    }

                } catch (Exception ignored) {
                    // 응답이 JSON이 아니면 상태 코드만 사용
                }

                System.err.println(
                        "[RP-08 Storage] HTTP=" + code
                                + ", errorCode=" + errorCode
                                + ", method=" + method
                );

                throw unavailable();
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | IllegalArgumentException e) {
            throw unavailable();
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private ApiException unavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "이의제기 증빙 파일을 저장하지 못했습니다."
        );
    }
}
