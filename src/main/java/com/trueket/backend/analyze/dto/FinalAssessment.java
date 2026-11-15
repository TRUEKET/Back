package com.trueket.backend.analyze.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record FinalAssessment(
        ChatAssessment chat,
        Stage reportStage,
        Stage finalStage,
        Source explanationSource,
        boolean raisedByReports,
        List<String> stageReportIdentifiers,
        @Schema(description="대화 자체에 의심이 있어 의심 제보를 허용하는지. 최초 값이 신고 등록에 전달됩니다.") boolean showSuspicionReport,
        @Schema(description="대화 또는 기존 신고 기준으로 신고 버튼을 표시하는지. showSuspicionReport와 다를 수 있습니다.") boolean showReportButton,
        boolean trueshotSecondary,
        String lookupWarning
) {
    public FinalAssessment {
        stageReportIdentifiers = List.copyOf(stageReportIdentifiers);
    }

    public enum Source { CHAT, REPORTS }
}
