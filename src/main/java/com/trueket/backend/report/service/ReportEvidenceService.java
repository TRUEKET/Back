
package com.trueket.backend.report.service;

import com.trueket.backend.analyze.dto.AnalysisReportSource;
import com.trueket.backend.analyze.service.AnalysisReportSourceService;
import com.trueket.backend.global.ai.AnalysisModelClient;
import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.identifier.util.IdentifierNormalizer;
import com.trueket.backend.report.dto.ReportEvidenceAnalysis;
import com.trueket.backend.report.dto.ReportEvidenceResponse;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReportEvidenceService {

    private final AnalysisReportSourceService analysisReportSourceService;
    private final ReportEvidenceImageValidator imageValidator;
    private final ReportEvidenceAiService aiService;
    private final ReportEvidenceImageProcessor imageProcessor;
    private final ReportEvidencePrivacyValidator privacyValidator;
    private final PendingReportEvidenceService pendingReportEvidenceService;

    public ReportEvidenceResponse verify(
            Long authenticatedUserId,
            UUID analysisId,
            String account,
            MultipartFile file,
            HttpSession session
    ) {
        // 1. 기존 JD 판독 세션 조회
        AnalysisReportSource source =
                analysisReportSourceService.load(analysisId, session);

        // 2. 로그인 사용자와 판독 세션 소유자 비교
        if (authenticatedUserId == null
                || source.userId() != authenticatedUserId.longValue()) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "판독 세션의 사용자 정보가 일치하지 않습니다."
            );
        }

        // 3. 신고 대상 계좌 정규화
        String targetAccount = normalizeAccount(account);

        // 4. JD가 추출한 계좌인지 확인
        boolean accountExists = source.identifiers().accounts()
                .stream()
                .map(this::normalizeAccount)
                .anyMatch(targetAccount::equals);

        if (!accountExists) {
            throw invalidEvidence();
        }

        // 5. 업로드 이미지 검증
        imageValidator.validate(file);

        // 6. AI-03에 전달할 이미지 준비
        AnalysisModelClient.Image image = prepareImage(file);

        // 7. AI-03 분석
        ReportEvidenceAnalysis analysis = aiService.analyze(image);

        // 8. 송금 완료 화면 여부 확인
        if (!analysis.transferCompleted()) {
            throw invalidEvidence();
        }

        // 9. AI가 추출한 수취 계좌 정규화
        if (analysis.bank() == null
                || analysis.accountNumber() == null) {
            throw invalidEvidence();
        }

        String detectedAccount = normalizeAccount(
                analysis.bank() + " " + analysis.accountNumber()
        );

        // 10. 신고 대상 계좌와 일치 여부 확인
        if (!Objects.equals(targetAccount, detectedAccount)) {
            throw invalidEvidence();
        }

        // 11. 계좌번호 위치 좌표 검증
        validateBoundingBox(analysis.accountBox());
        // 12. 수취 계좌번호 영역만 추출
        byte[] croppedImage = imageProcessor.cropAccountNumber(
                file,
                analysis.accountBox()
        );

        // 13. 이미지 가공 결과 검증
        if (croppedImage.length == 0
                || croppedImage.length > 5L * 1024 * 1024) {
            throw invalidEvidence();
        }

        // 14. 잘라낸 이미지의 개인정보 노출 여부 검사
        privacyValidator.validate(croppedImage);

        // 15. 모든 검증을 통과한 이미지만 임시 저장
        UUID evidenceToken = pendingReportEvidenceService.save(
                authenticatedUserId,
                analysisId,
                targetAccount,
                croppedImage
        );

        // 16. 검증 결과와 임시 증빙 토큰 반환
        return new ReportEvidenceResponse(
                true,
                "송금 내역의 수취 계좌가 신고 대상과 일치합니다.",
                evidenceToken
        );
    }

    private String normalizeAccount(String raw) {
        try {
            var normalized = IdentifierNormalizer.normalize(raw);

            if (normalized.type() != IdentifierType.ACCOUNT) {
                throw invalidEvidence();
            }

            return normalized.value();

        } catch (IllegalArgumentException e) {
            throw invalidEvidence();
        }
    }

    private AnalysisModelClient.Image prepareImage(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();

            // 이미지 검증은 앞 단계에서 완료
            // 현재는 원본 이미지를 AI 분석에만 전달
            String mimeType = detectMimeType(bytes);

            return new AnalysisModelClient.Image(
                    mimeType,
                    Base64.getEncoder().encodeToString(bytes)
            );

        } catch (IOException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "송금 사진을 읽지 못했습니다."
            );
        }
    }

    private String detectMimeType(byte[] bytes) {
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G') {
            return "image/png";
        }

        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }

        throw invalidEvidence();
    }

    private void validateBoundingBox(
            ReportEvidenceAnalysis.BoundingBox box
    ) {
        if (box == null
                || !Double.isFinite(box.x1())
                || !Double.isFinite(box.y1())
                || !Double.isFinite(box.x2())
                || !Double.isFinite(box.y2())
                || box.x1() < 0 || box.y1() < 0
                || box.x2() > 1 || box.y2() > 1
                || box.x1() >= box.x2()
                || box.y1() >= box.y2()) {
            throw invalidEvidence();
        }
    }

    private ApiException invalidEvidence() {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "송금 내역을 확인하지 못했어요. 신고 계좌와 송금 사진을 확인해 주세요."
        );
    }
}
