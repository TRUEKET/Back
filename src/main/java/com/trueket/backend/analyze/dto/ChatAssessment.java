package com.trueket.backend.analyze.dto;

import com.trueket.backend.analyze.dto.Criterion;
import com.trueket.backend.analyze.dto.SignalStrength;

import java.util.List;
import java.util.Map;

public record ChatAssessment(
        Stage stage,
        List<Evidence> countedSignals,
        List<Evidence> informationalSignals,
        Map<Criterion, Stage> criteria,
        boolean accountNoticeRequired,
        boolean showTrueshotOnResult,
        String dictionaryVersion
) {
    public ChatAssessment {
        countedSignals = List.copyOf(countedSignals);
        informationalSignals = List.copyOf(informationalSignals);
        criteria = Map.copyOf(criteria);
    }

    public record Evidence(String id, Criterion criterion, SignalStrength strength,
                           String sentenceId, String quote, String reason) {}
}
