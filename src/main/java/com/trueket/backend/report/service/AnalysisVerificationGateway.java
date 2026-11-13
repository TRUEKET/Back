package com.trueket.backend.report.service;

import com.trueket.backend.report.entity.enums.ReportKind;

import java.util.List;

public interface AnalysisVerificationGateway {

    VerifiedAnalysis verify(
            Long userId,
            String analysisProof
    );

    record VerifiedAnalysis(
            ReportKind kind,
            boolean reportable,
            boolean suspicionFromListOnly,
            List<String> identifiers,
            List<String> evidenceQuotes
    ) {
    }
}
