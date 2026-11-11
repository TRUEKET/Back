package com.trueket.backend.analyze.dto;

import com.trueket.backend.report.entity.enums.TradeType;

import java.util.List;
import java.util.UUID;

public record AnalyzeResponse(UUID analyzeId, boolean readable, TradeType detectedType,
                              FinalAssessment assessment, List<MaskedIdentifier> identifiers,
                              String otherSuspicious, String message) {
    public record MaskedIdentifier(String type, String value) {}
}
