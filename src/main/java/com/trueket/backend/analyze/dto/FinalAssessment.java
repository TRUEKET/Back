package com.trueket.backend.analyze.dto;

import java.util.List;

public record FinalAssessment(
        ChatAssessment chat,
        Stage reportStage,
        Stage finalStage,
        Source explanationSource,
        boolean raisedByReports,
        List<String> stageReportIdentifiers,
        boolean showSuspicionReport,
        boolean showReportButton,
        boolean trueshotSecondary,
        String lookupWarning
) {
    public FinalAssessment {
        stageReportIdentifiers = List.copyOf(stageReportIdentifiers);
    }

    public enum Source { CHAT, REPORTS }
}
