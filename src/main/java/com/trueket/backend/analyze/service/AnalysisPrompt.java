package com.trueket.backend.analyze.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class AnalysisPrompt {
    private final ObjectMapper json = new ObjectMapper();
    private final String instructions;
    private final String dictionary;
    private final JsonNode schema;

    public AnalysisPrompt() throws IOException {
        try (var input = new ClassPathResource("analysis/prompt.txt").getInputStream()) {
            instructions = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var input = new ClassPathResource("analysis/signals.json").getInputStream()) {
            JsonNode data = json.readTree(input);
            var enabled = json.createArrayNode();
            data.path("signals").forEach(signal -> { if (signal.path("enabled").asBoolean()) enabled.add(signal); });
            dictionary = enabled.toString();
        }
        try (var input = new ClassPathResource("analysis/response-schema.json").getInputStream()) {
            schema = json.readTree(input);
        }
    }

    public String text(String additionalInfo) {
        try {
            return instructions + "\n<signal_dictionary>\n" + dictionary
                    + "\n</signal_dictionary>\n<untrusted_additional_info>\n"
                    + json.writeValueAsString(Map.of("text", additionalInfo == null ? "" : additionalInfo))
                    + "\n</untrusted_additional_info>";
        } catch (IOException e) {
            throw new IllegalStateException("판독 입력을 구성하지 못했습니다.", e);
        }
    }

    public JsonNode schema() { return schema; }
}
