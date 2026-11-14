package com.trueket.backend.analyze.service;

import com.trueket.backend.global.ai.AnalysisModelClient.Image;
import com.trueket.backend.global.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Component
public class CaptureProcessor {
    public List<Image> prepare(List<MultipartFile> captures, String additionalInfo) {
        if (captures == null || captures.isEmpty() || captures.size() > 10) {
            throw invalid("대화 캡처를 1장 이상, 최대 10장 넣어 주세요");
        }
        if (additionalInfo != null && additionalInfo.codePointCount(0, additionalInfo.length()) > 500) {
            throw invalid("추가 정보는 500자 이하로 넣어 주세요");
        }
        List<Image> images = new ArrayList<>();
        for (MultipartFile capture : captures) {
            if (capture == null || capture.isEmpty() || capture.getSize() > 10 * 1024 * 1024) {
                throw invalid("사진은 한 장당 10MB 이하로 넣어 주세요");
            }
            images.add(normalize(capture));
        }
        return List.copyOf(images);
    }

    private Image normalize(MultipartFile capture) {
        try (var source = capture.getInputStream();
             var input = new javax.imageio.stream.MemoryCacheImageInputStream(source)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalid("읽을 수 있는 PNG 또는 JPG 이미지를 올려 주세요");
            }
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 100_000_000) {
                    throw invalid("이미지 해상도가 너무 커요. 캡처 크기를 줄여 주세요");
                }
                var parameters = reader.getDefaultReadParam();
                int sampling = Math.max(1, Math.max(width, height) / 3072);
                parameters.setSourceSubsampling(sampling, sampling, 0, 0);
                BufferedImage image = reader.read(0, parameters);
                byte[] encoded = png(image);
                while (encoded.length > 1_000_000) {
                    image = smaller(image);
                    encoded = png(image);
                }
                return new Image("image/png", Base64.getEncoder().encodeToString(encoded));
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException e) {
            throw invalid("이미지를 읽지 못했어요. PNG 또는 JPG로 다시 올려 주세요");
        }
    }

    private byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private BufferedImage smaller(BufferedImage image) {
        int width = Math.max(1, image.getWidth() * 3 / 4);
        int height = Math.max(1, image.getHeight() * 3 / 4);
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = scaled.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}