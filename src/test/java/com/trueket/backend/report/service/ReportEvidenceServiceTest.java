
package com.trueket.backend.report.service;

import com.trueket.backend.analyze.dto.AnalysisReportSource;
import com.trueket.backend.analyze.service.AnalysisReportSourceService;
import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.report.dto.ReportEvidenceAnalysis;
import com.trueket.backend.report.dto.ReportEvidenceResponse;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportEvidenceServiceTest {

    private AnalysisReportSourceService sourceService;
    private ReportEvidenceImageValidator imageValidator;
    private ReportEvidenceAiService aiService;
    private ReportEvidenceImageProcessor imageProcessor;
    private ReportEvidencePrivacyValidator privacyValidator;
    private PendingReportEvidenceService pendingService;

    private ReportEvidenceService service;

    private HttpSession session;
    private UUID analysisId;
    private MockMultipartFile file;

    private final Long userId = 15L;

    private final String account = "신한 110-22-345678";
    private final String normalizedAccount = "SHINHAN:11022345678";

    private final byte[] croppedImage = new byte[]{1, 2, 3};

    @BeforeEach
    void setUp() {
        sourceService = mock(AnalysisReportSourceService.class);
        imageValidator = mock(ReportEvidenceImageValidator.class);
        aiService = mock(ReportEvidenceAiService.class);
        imageProcessor = mock(ReportEvidenceImageProcessor.class);
        privacyValidator = mock(ReportEvidencePrivacyValidator.class);
        pendingService = mock(PendingReportEvidenceService.class);

        session = mock(HttpSession.class);
        analysisId = UUID.randomUUID();

        service = new ReportEvidenceService(
                sourceService,
                imageValidator,
                aiService,
                imageProcessor,
                privacyValidator,
                pendingService
        );

        // PNG 파일 시그니처를 포함한 테스트 데이터
        file = new MockMultipartFile(
                "file",
                "transfer.png",
                "image/png",
                new byte[]{
                        (byte) 0x89, 'P', 'N', 'G',
                        13, 10, 26, 10
                }
        );
    }

    private void mockSource() {
        AnalysisReportSource source = mock(
                AnalysisReportSource.class,
                RETURNS_DEEP_STUBS
        );

        when(source.userId()).thenReturn(15L);

        when(source.identifiers().accounts())
                .thenReturn(List.of(account));

        when(sourceService.load(analysisId, session))
                .thenReturn(source);
    }

    private void mockValidAnalysis() {
        ReportEvidenceAnalysis analysis =
                new ReportEvidenceAnalysis(
                        true,
                        "신한",
                        "110-22-345678",
                        new ReportEvidenceAnalysis.BoundingBox(
                                0.1, 0.1, 0.8, 0.3
                        ),
                        0.99
                );

        when(aiService.analyze(any()))
                .thenReturn(analysis);

        when(imageProcessor.cropAccountNumber(
                eq(file),
                any(ReportEvidenceAnalysis.BoundingBox.class)
        )).thenReturn(croppedImage);
    }

    @Test
    void 정상_송금_사진이면_토큰을_반환한다() {
        mockSource();
        mockValidAnalysis();

        UUID expectedToken = UUID.randomUUID();

        when(pendingService.save(
                eq(userId),
                eq(analysisId),
                eq(normalizedAccount),
                same(croppedImage)
        )).thenReturn(expectedToken);

        ReportEvidenceResponse response = service.verify(
                userId,
                analysisId,
                account,
                file,
                session
        );

        assertThat(response.verified()).isTrue();
        assertThat(response.evidenceToken())
                .isEqualTo(expectedToken);

        verify(imageValidator).validate(file);
        verify(privacyValidator).validate(croppedImage);

        verify(pendingService).save(
                userId,
                analysisId,
                normalizedAccount,
                croppedImage
        );

        // 개인정보 검증 이후에만 저장되어야 함
        var order = inOrder(privacyValidator, pendingService);

        order.verify(privacyValidator)
                .validate(croppedImage);

        order.verify(pendingService)
                .save(
                        userId,
                        analysisId,
                        normalizedAccount,
                        croppedImage
                );
    }

    @Test
    void 송금_계좌가_다르면_저장하지_않는다() {
        mockSource();

        ReportEvidenceAnalysis analysis =
                new ReportEvidenceAnalysis(
                        true,
                        "신한",
                        "99999999999",
                        new ReportEvidenceAnalysis.BoundingBox(
                                0.1, 0.1, 0.8, 0.3
                        ),
                        0.99
                );

        when(aiService.analyze(any()))
                .thenReturn(analysis);

        assertThatThrownBy(() ->
                service.verify(
                        userId,
                        analysisId,
                        account,
                        file,
                        session
                )
        ).isInstanceOf(ApiException.class);

        verifyNoInteractions(
                imageProcessor,
                privacyValidator,
                pendingService
        );
    }

    @Test
    void 개인정보_검증에_실패하면_저장하지_않는다() {
        mockSource();
        mockValidAnalysis();

        doThrow(new ApiException(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "개인정보가 포함되어 있습니다."
        )).when(privacyValidator).validate(croppedImage);

        assertThatThrownBy(() ->
                service.verify(
                        userId,
                        analysisId,
                        account,
                        file,
                        session
                )
        ).isInstanceOf(ApiException.class);

        verify(privacyValidator).validate(croppedImage);

        verifyNoInteractions(pendingService);
    }

    @Test
    void 임시_증빙_저장에_실패하면_성공_응답을_반환하지_않는다() {
        mockSource();
        mockValidAnalysis();

        when(pendingService.save(
                eq(userId),
                eq(analysisId),
                eq(normalizedAccount),
                same(croppedImage)
        )).thenThrow(new RuntimeException("Storage failure"));

        assertThatThrownBy(() ->
                service.verify(
                        userId,
                        analysisId,
                        account,
                        file,
                        session
                )
        ).isInstanceOf(RuntimeException.class)
                .hasMessage("Storage failure");

        verify(privacyValidator).validate(croppedImage);

        verify(pendingService).save(
                userId,
                analysisId,
                normalizedAccount,
                croppedImage
        );
    }
}
