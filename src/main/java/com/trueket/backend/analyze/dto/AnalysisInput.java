package com.trueket.backend.analyze.dto;

import com.trueket.backend.report.entity.enums.TradeType;

import java.util.List;
import java.util.Objects;

public record AnalysisInput(
        TradeType tradeType,
        boolean typeMismatch,
        List<Sentence> sentences,
        List<SignalCandidate> signals
) {
    public AnalysisInput {
        tradeType = tradeType == null ? TradeType.OTHER : tradeType;
        sentences = List.copyOf(sentences);
        signals = List.copyOf(signals);
    }

    public enum Speaker { SELLER, BUYER, UNKNOWN }

    public record Sentence(String id, Speaker speaker, String text) {
        public Sentence {
            Objects.requireNonNull(id);
            Objects.requireNonNull(speaker);
            Objects.requireNonNull(text);
            if (id.isBlank()) {
                throw new IllegalArgumentException("문장 식별자가 필요합니다.");
            }
        }
    }

    public record SignalCandidate(String id, String sentenceId, String quote, String reason) {}
}