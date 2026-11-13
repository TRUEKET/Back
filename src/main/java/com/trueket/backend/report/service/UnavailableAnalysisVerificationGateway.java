package com.trueket.backend.report.service;

import com.trueket.backend.global.exception.BusinessException;
import com.trueket.backend.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class UnavailableAnalysisVerificationGateway
        implements AnalysisVerificationGateway {

    @Override
    public VerifiedAnalysis verify(
            Long userId,
            String analysisProof
    ) {
        throw new BusinessException(
                ErrorCode.ANALYSIS_NOT_AVAILABLE
        );
    }
}
