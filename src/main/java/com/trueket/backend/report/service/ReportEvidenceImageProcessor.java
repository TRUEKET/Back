
package com.trueket.backend.report.service;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.report.dto.ReportEvidenceAnalysis;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Component
public class ReportEvidenceImageProcessor {

    private static final int MIN_CROP_WIDTH = 40;
    private static final int MIN_CROP_HEIGHT = 12;

    public byte[] cropAccountNumber(
            MultipartFile file,
            ReportEvidenceAnalysis.BoundingBox box
    ) {
        try {
            BufferedImage original = ImageIO.read(file.getInputStream());

            if (original == null) {
                throw invalid();
            }

            int imageWidth = original.getWidth();
            int imageHeight = original.getHeight();

            // AI 좌표: 0~1 범위 → 실제 이미지 픽셀
            int x1 = (int) Math.floor(box.x1() * imageWidth);
            int y1 = (int) Math.floor(box.y1() * imageHeight);
            int x2 = (int) Math.ceil(box.x2() * imageWidth);
            int y2 = (int) Math.ceil(box.y2() * imageHeight);

            x1 = Math.max(0, Math.min(x1, imageWidth));
            y1 = Math.max(0, Math.min(y1, imageHeight));
            x2 = Math.max(0, Math.min(x2, imageWidth));
            y2 = Math.max(0, Math.min(y2, imageHeight));

            int cropWidth = x2 - x1;
            int cropHeight = y2 - y1;

            if (cropWidth < MIN_CROP_WIDTH
                    || cropHeight < MIN_CROP_HEIGHT) {
                throw invalid();
            }

            BufferedImage cropped = new BufferedImage(
                    cropWidth,
                    cropHeight,
                    BufferedImage.TYPE_INT_RGB
            );

            Graphics2D graphics = cropped.createGraphics();

            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, cropWidth, cropHeight);

                graphics.drawImage(
                        original,
                        0, 0, cropWidth, cropHeight,
                        x1, y1, x2, y2,
                        null
                );
            } finally {
                graphics.dispose();
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();

            if (!ImageIO.write(cropped, "png", output)) {
                throw invalid();
            }

            return output.toByteArray();

        } catch (IOException | IllegalArgumentException e) {
            throw invalid();
        }
    }

    private ApiException invalid() {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "계좌번호 영역을 추출하지 못했어요. 다른 사진으로 다시 시도해 주세요."
        );
    }
}
