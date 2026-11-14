package com.trueket.backend.analyze.service;

import com.trueket.backend.analyze.dto.AnalysisInput;
import com.trueket.backend.analyze.dto.AnalysisInput.Sentence;
import com.trueket.backend.analyze.dto.AnalysisInput.SignalCandidate;
import com.trueket.backend.analyze.dto.AnalysisInput.Speaker;
import com.trueket.backend.analyze.dto.ChatAssessment;
import com.trueket.backend.analyze.dto.ChatAssessment.Evidence;
import com.trueket.backend.analyze.dto.Stage;
import com.trueket.backend.analyze.dto.Criterion;
import com.trueket.backend.analyze.dto.SignalStrength;
import com.trueket.backend.report.entity.enums.TradeType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatStageService {
    private static final List<Criterion> CORE_CRITERIA = List.of(
            Criterion.A, Criterion.B, Criterion.C, Criterion.D, Criterion.E, Criterion.F);
    private static final Comparator<Evidence> PRIORITY = Comparator
            .comparing(Evidence::strength)
            .thenComparing(Evidence::criterion)
            .thenComparing(Evidence::id)
            .thenComparing(Evidence::quote);
    private final SignalDictionary dictionary;

    public ChatStageService(SignalDictionary dictionary) {
        this.dictionary = dictionary;
    }

    public ChatAssessment assess(AnalysisInput input) {
        Map<String, Sentence> sentences = new LinkedHashMap<>();
        for (Sentence sentence : input.sentences()) {
            if (sentences.putIfAbsent(sentence.id(), sentence) != null) {
                throw new IllegalArgumentException("문장 식별자는 중복될 수 없습니다.");
            }
        }
        boolean accountTrade = isAccountTrade(input.tradeType());
        List<Evidence> informational = new ArrayList<>();
        Map<String, List<Evidence>> grouped = new LinkedHashMap<>();
        for (SignalCandidate candidate : input.signals()) {
            Evidence evidence = validate(candidate, sentences);
            if (evidence == null) {
                continue;
            }
            if (accountTrade && evidence.id().equals("S-A1")) {
                informational.add(evidence);
                continue;
            }
            grouped.computeIfAbsent(evidence.sentenceId(), ignored -> new ArrayList<>()).add(evidence);
        }
        Map<String, Integer> counts = new HashMap<>();
        List<Evidence> counted = new ArrayList<>();
        for (List<Evidence> candidates : grouped.values()) {
            List<Evidence> ordered = candidates.stream().sorted(PRIORITY).toList();
            Evidence first = ordered.get(0);
            addCounted(first, counts, counted);
            // Explicit A1/F1 exception in the service specification and T01.
            if (input.typeMismatch() && first.id().equals("S-A1")) {
                ordered.stream().filter(evidence -> evidence.id().equals("S-F1")).findFirst()
                        .ifPresent(evidence -> addCounted(evidence, counts, counted));
            }
        }
        EnumMap<Criterion, Stage> criteria = new EnumMap<>(Criterion.class);
        CORE_CRITERIA.forEach(criterion -> criteria.put(criterion, Stage.SAFE));
        for (Evidence evidence : counted) {
            if (evidence.criterion() != Criterion.X) {
                Stage stage = evidence.strength() == SignalStrength.STRONG ? Stage.DANGER : Stage.SUSPECT;
                criteria.merge(evidence.criterion(), stage, Stage::moreSevere);
            }
        }
        return new ChatAssessment(stage(counted), counted, informational, criteria,
                accountTrade, !accountTrade, dictionary.version());
    }

    private Evidence validate(SignalCandidate candidate, Map<String, Sentence> sentences) {
        if (candidate.id() == null || candidate.sentenceId() == null || candidate.quote() == null
                || candidate.quote().isBlank() || candidate.reason() == null || candidate.reason().isBlank()) {
            return null;
        }
        Sentence sentence = sentences.get(candidate.sentenceId());
        if (sentence == null || sentence.speaker() != Speaker.SELLER || !sentence.text().contains(candidate.quote())) {
            return null;
        }
        return dictionary.enabledSignal(candidate.id())
                .map(definition -> new Evidence(definition.id(), definition.criterion(), definition.strength(),
                        sentence.id(), candidate.quote(), candidate.reason()))
                .orElse(null);
    }

    private void addCounted(Evidence evidence, Map<String, Integer> counts, List<Evidence> counted) {
        int count = counts.getOrDefault(evidence.id(), 0);
        if (count < 2) {
            counted.add(evidence);
            counts.put(evidence.id(), count + 1);
        }
    }

    private Stage stage(List<Evidence> signals) {
        if (signals.stream().anyMatch(evidence -> evidence.strength() == SignalStrength.STRONG)) {
            return Stage.DANGER;
        }
        long knownSignals = signals.stream().filter(evidence -> evidence.criterion() != Criterion.X).count();
        long criteria = signals.stream().map(Evidence::criterion)
                .filter(criterion -> criterion != Criterion.X).distinct().count();
        if (signals.size() >= 3 && knownSignals >= 2 && criteria >= 2) {
            return Stage.DANGER;
        }
        return signals.isEmpty() ? Stage.SAFE : Stage.SUSPECT;
    }

    private boolean isAccountTrade(TradeType type) {
        return type == TradeType.AOM || type == TradeType.PROXY || type == TradeType.CANCEL;
    }
}