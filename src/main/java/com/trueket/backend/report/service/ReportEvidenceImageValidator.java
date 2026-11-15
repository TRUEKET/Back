
package com.trueket.backend.report.service;

import com.trueket.backend.global.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

@Component
public class ReportEvidenceImageValidator {

    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final long MAX_PIXELS = 40_000_000L;

    private static final Set<String> ALLOWED_FORMATS =
            Set.of("jpeg", "png");

    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("송금 사진을 첨부해 주세요.");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw invalid("송금 사진은 5MB 이하로 올려 주세요.");
        }

        try (ImageInputStream input =
                     ImageIO.createImageInputStream(file.getInputStream())) {

            if (input == null) {
                throw invalid("이미지를 읽을 수 없습니다.");
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);

            if (!readers.hasNext()) {
                throw invalid("JPEG, PNG 이미지만 업로드할 수 있습니다.");
            }

            ImageReader reader = readers.next();

            try {
                String format = reader.getFormatName().toLowerCase();

                if (format.equals("jpg")) {
                    format = "jpeg";
                }

                if (!ALLOWED_FORMATS.contains(format)) {
                    throw invalid("지원하지 않는 이미지 형식입니다.");
                }

                reader.setInput(input);

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);

                if (width <= 0 || height <= 0 ||
                        (long) width * height > MAX_PIXELS) {
                    throw invalid("이미지 해상도가 너무 큽니다.");
                }

            } finally {
                reader.dispose();
            }

        } catch (IOException | IllegalArgumentException e) {
            throw invalid("이미지를 읽지 못했습니다.");
        }
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
