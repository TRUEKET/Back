
package com.trueket.backend.report.service;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.report.dto.ReportEvidenceAnalysis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.*;

class ReportEvidenceImageProcessorTest {

    private ReportEvidenceImageProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ReportEvidenceImageProcessor();
    }

    @Test
    void 정상적인_계좌번호_영역을_추출한다() throws Exception {
        MockMultipartFile file = createImage(400, 200);

        var box = new ReportEvidenceAnalysis.BoundingBox(
                0.25, 0.25, 0.75, 0.50
        );

        byte[] result = processor.cropAccountNumber(file, box);

        BufferedImage cropped = ImageIO.read(
                new ByteArrayInputStream(result)
        );

        assertThat(cropped).isNotNull();
        assertThat(cropped.getWidth()).isEqualTo(200);
        assertThat(cropped.getHeight()).isEqualTo(50);
    }

    @Test
    void 이미지_좌표가_전체_영역이면_전체_크기로_추출한다() throws Exception {
        MockMultipartFile file = createImage(400, 200);

        var box = new ReportEvidenceAnalysis.BoundingBox(
                0.0, 0.0, 1.0, 1.0
        );

        byte[] result = processor.cropAccountNumber(file, box);

        BufferedImage cropped = ImageIO.read(
                new ByteArrayInputStream(result)
        );

        assertThat(cropped.getWidth()).isEqualTo(400);
        assertThat(cropped.getHeight()).isEqualTo(200);
    }

    @Test
    void 너무_작은_영역은_거부한다() throws Exception {
        MockMultipartFile file = createImage(400, 200);

        var box = new ReportEvidenceAnalysis.BoundingBox(
                0.10, 0.10, 0.15, 0.13
        );

        assertThatThrownBy(
                () -> processor.cropAccountNumber(file, box)
        ).isInstanceOf(ApiException.class);
    }

    @Test
    void 이미지가_아니면_거부한다() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "invalid.png",
                "image/png",
                "not an image".getBytes()
        );

        var box = new ReportEvidenceAnalysis.BoundingBox(
                0.0, 0.0, 1.0, 1.0
        );

        assertThatThrownBy(
                () -> processor.cropAccountNumber(file, box)
        ).isInstanceOf(ApiException.class);
    }

    private MockMultipartFile createImage(
            int width,
            int height
    ) throws Exception {
        BufferedImage image = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_INT_RGB
        );

        Graphics2D graphics = image.createGraphics();

        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);

        return new MockMultipartFile(
                "file",
                "transfer.png",
                "image/png",
                output.toByteArray()
        );
    }
}
