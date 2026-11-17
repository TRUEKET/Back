
package com.trueket.backend.report.service;

import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.identifier.util.IdentifierHashService;
import com.trueket.backend.report.entity.PendingReportEvidence;
import com.trueket.backend.report.repository.PendingReportEvidenceRepository;
import com.trueket.backend.global.exception.BusinessException;
import com.trueket.backend.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;

import java.util.List;
import java.util.UUID;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PendingReportEvidenceService {

    private final PendingReportEvidenceRepository repository;
    private final SupabaseReportEvidenceStore storage;
    private final IdentifierHashService hashService;

    public UUID save(
            Long userId,
            UUID analysisId,
            String normalizedAccount,
            byte[] verifiedImage
    ) {
        if (userId == null || userId <= 0
                || analysisId == null
                || normalizedAccount == null
                || normalizedAccount.isBlank()
                || verifiedImage == null
                || verifiedImage.length == 0) {
            throw new IllegalArgumentException(
                    "임시 증빙 저장 정보가 올바르지 않습니다."
            );
        }

        // 1. 계좌번호를 직접 저장하지 않고 해시로 변환
        String accountHash = hashService.hash(
                IdentifierType.ACCOUNT,
                normalizedAccount
        );

        // 2. 임시 Storage 경로 생성
        String storageKey = storage.createPendingKey(userId);

        // 3. 임시 증빙 메타데이터 생성
        PendingReportEvidence pending =
                PendingReportEvidence.create(
                        userId,
                        analysisId,
                        accountHash,
                        storageKey
                );

        // 4. 이미지 업로드
        storage.upload(storageKey, verifiedImage);

        // 5. DB 저장
        try {
            repository.saveAndFlush(pending);
        } catch (RuntimeException e) {
            // DB 저장 실패 시 업로드한 파일 삭제
            try {
                storage.deletePending(storageKey);
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }

            throw e;
        }

        // 6. 프론트엔드에 전달할 증빙 토큰
        return pending.getToken();
    }



    @Transactional
    public PendingReportEvidence consume(
            UUID token,
            Long userId,
            UUID analysisId,
            List<String> normalizedAccounts
    ) {
        // 1. 필수 입력 검증
        if (token == null
                || userId == null
                || analysisId == null
                || normalizedAccounts == null
                || normalizedAccounts.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST
            );
        }

        // 2. 증빙 토큰을 한 번만 잠금 조회
        PendingReportEvidence pending =
                repository.findByTokenForUpdate(token)
                        .orElseThrow(() ->
                                new BusinessException(
                                        ErrorCode.INVALID_REQUEST
                                )
                        );

        // 3. 사용자 소유권 검증
        if (!Objects.equals(pending.getUserId(), userId)) {
            throw new BusinessException(
                    ErrorCode.UNAUTHORIZED
            );
        }

        // 4. JD 판독 세션 일치 여부 검증
        if (!Objects.equals(
                pending.getAnalysisId(),
                analysisId
        )) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST
            );
        }

        // 5. 토큰 만료 여부 검증
        OffsetDateTime now = OffsetDateTime.now();

        if (pending.isExpired(now)) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST
            );
        }

        // 6. 이미 사용한 토큰인지 검증
        if (pending.isConsumed()) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST
            );
        }

        // 7. JD에서 추출한 계좌 중
        // 증빙 토큰과 일치하는 계좌가 있는지 확인
        boolean accountMatched = false;

        for (String normalizedAccount : normalizedAccounts) {

            if (normalizedAccount == null
                    || normalizedAccount.isBlank()) {
                throw new BusinessException(
                        ErrorCode.INVALID_REQUEST
                );
            }

            String accountHash = hashService.hash(
                    IdentifierType.ACCOUNT,
                    normalizedAccount
            );

            if (Objects.equals(
                    pending.getAccountHash(),
                    accountHash
            )) {
                accountMatched = true;
                break;
            }
        }

        if (!accountMatched) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST
            );
        }

        // 8. 모든 검증 통과 후 토큰 사용 처리
        pending.consume(now);

        return pending;
    }


}
