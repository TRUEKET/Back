package com.trueket.backend.trueshot.service;

import com.trueket.backend.global.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Component
public class TrueshotImageInput {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public record Prepared(byte[] bytes, String mimeType) {}

    public Prepared prepare(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("사진을 올려 주세요.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"사진은 5MB 이하로 올려 주세요.");
        }
        try (var source = file.getInputStream(); var input = new MemoryCacheImageInputStream(source)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("JPEG 또는 PNG 사진을 올려 주세요.");
            var reader = readers.next();
            try {
                String format=reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!java.util.Set.of("jpeg","jpg","png").contains(format)) {
                    throw invalid("JPEG 또는 PNG 사진을 올려 주세요.");
                }
                reader.setInput(input);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 320 || height < 320 || (long) width * height > 40_000_000L) {
                    throw invalid("사진 해상도를 확인하고 다시 찍어 주세요.");
                }
                var params = reader.getDefaultReadParam();
                int sampling = Math.max(1, Math.max(width, height) / 2048);
                params.setSourceSubsampling(sampling, sampling, 0, 0);
                BufferedImage decoded = reader.read(0, params);
                BufferedImage rgb = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
                var graphics = rgb.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(decoded, 0, 0, null);
                } finally {
                    graphics.dispose();
                }
                byte[] bytes = jpeg(rgb);
                if (bytes.length > MAX_BYTES) throw invalid("사진 크기를 줄여 다시 올려 주세요.");
                return new Prepared(bytes, "image/jpeg");
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException e) {
            throw invalid("사진을 읽지 못했어요. 다시 찍어 주세요.");
        }
    }

    private byte[] jpeg(BufferedImage image) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("JPEG 인코더가 없습니다.");
        var writer = writers.next();
        try (var output = new ByteArrayOutputStream(); var stream = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(stream);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(0.82f);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), params);
            stream.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
