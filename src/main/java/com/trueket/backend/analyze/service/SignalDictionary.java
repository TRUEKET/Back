package com.trueket.backend.analyze.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.trueket.backend.analyze.dto.Criterion;
import com.trueket.backend.analyze.dto.SignalStrength;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class SignalDictionary {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Definition(String id, Criterion criterion, String criterionName, String name,
                             SignalStrength strength, boolean enabled, String definition,
                             List<String> notSignal, List<String> examples, List<String> counterExamples) {
        public Definition {
            examples = List.copyOf(examples);
            notSignal = notSignal == null ? List.of() : List.copyOf(notSignal);
            counterExamples = counterExamples == null ? List.of() : List.copyOf(counterExamples);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DictionaryFile(String version, List<Definition> signals) {}
    private final String version;
    private final Map<String, Definition> definitions;

    public SignalDictionary() throws IOException {
        this(new ClassPathResource("analysis/signals.json").getInputStream());
    }

    SignalDictionary(java.io.InputStream input) throws IOException {
        DictionaryFile file;
        try (var stream = input) {
            file = new ObjectMapper().readValue(stream, DictionaryFile.class);
        }
        Map<String, Definition> loaded = new LinkedHashMap<>();
        for (Definition definition : file.signals()) {
            if (definition.id() == null || definition.criterion() == null || definition.strength() == null
                    || loaded.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalStateException("신호 사전의 ID·기준·세기를 확인해 주세요.");
            }
        }
        version = file.version();
        definitions = Map.copyOf(loaded);
    }

    public Optional<Definition> enabledSignal(String id) {
        return Optional.ofNullable(definitions.get(id)).filter(Definition::enabled);
    }

    public String version() {
        return version;
    }
}
