package com.trueket.backend.analyze.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.analyze.dto.ModelExtraction;
import com.trueket.backend.global.ai.ModelException;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;

@Component
public class ModelExtractionParser {
    private final ObjectMapper json = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);

    public ModelExtraction parse(String text, int imageCount) {
        try {
            var tree = json.readTree(text);
            if (tree == null || !tree.path("readable").isBoolean()) {
                throw invalid();
            }
            if (!tree.get("readable").booleanValue()) {
                return new ModelExtraction(false, "etc", false, List.of(), List.of(),
                        new ModelExtraction.Extracted(List.of(), List.of(), List.of(), List.of()), null, List.of());
            }
            if (!tree.path("type_mismatch").isBoolean() || !tree.path("sensitive_values").isArray()) {
                throw invalid();
            }
            ModelExtraction result = json.treeToValue(tree, ModelExtraction.class);
            if (!List.of("transfer", "aom", "proxy", "cancel", "etc").contains(result.detectedType())
                    || result.sentences() == null || result.sentences().isEmpty() || result.sentences().size() > 1000
                    || result.signals() == null || result.signals().size() > 1000 || result.extracted() == null
                    || result.sensitiveValues().stream().anyMatch(value -> value == null || value.isBlank())) {
                throw invalid();
            }
            var ids = new HashSet<String>();
            for (var signal : result.signals()) {
                if (signal == null || signal.id() == null || signal.sentenceId() == null
                        || signal.quote() == null || signal.reason() == null) throw invalid();
            }
            for (var sentence : result.sentences()) {
                if (sentence.id() == null || sentence.id().isBlank() || !ids.add(sentence.id())
                        || sentence.speaker() == null || sentence.text() == null || sentence.text().isBlank()
                        || sentence.imageIndex() == null || sentence.imageIndex() < 1 || sentence.imageIndex() > imageCount) {
                    throw invalid();
                }
            }
            for (List<String> values : List.of(result.extracted().accounts(), result.extracted().phones(),
                    result.extracted().xAccounts(), result.extracted().links())) {
                if (values.size() > 10 || values.stream().anyMatch(value -> value == null || value.isBlank())) {
                    throw invalid();
                }
            }
            return result;
        } catch (java.io.IOException | IllegalArgumentException | NullPointerException e) {
            throw invalid();
        }
    }

    private ModelException invalid() {
        return new ModelException(ModelException.Kind.INVALID_RESPONSE);
    }
}
