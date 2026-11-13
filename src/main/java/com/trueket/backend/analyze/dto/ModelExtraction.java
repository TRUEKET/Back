package com.trueket.backend.analyze.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ModelExtraction(
        boolean readable,
        @JsonProperty("detected_type") String detectedType,
        @JsonProperty("type_mismatch") boolean typeMismatch,
        List<SourceSentence> sentences,
        List<SourceSignal> signals,
        Extracted extracted,
        @JsonProperty("other_suspicious") String otherSuspicious,
        @JsonProperty("sensitive_values") List<String> sensitiveValues
) {
    public record SourceSentence(String id, AnalysisInput.Speaker speaker, String text,
                                 @JsonProperty("image_index") Integer imageIndex) {}
    public record SourceSignal(String id, @JsonProperty("sentence_id") String sentenceId, String quote, String reason) {}
    public record Extracted(List<String> accounts, List<String> phones,
                            @JsonProperty("x_accounts") List<String> xAccounts, List<String> links) {}
}