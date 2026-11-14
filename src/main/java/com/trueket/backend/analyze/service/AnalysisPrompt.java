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
        var enabledIds = json.createArrayNode();
        try (var input = new ClassPathResource("analysis/signals.json").getInputStream()) {
            JsonNode data = json.readTree(input);
            var enabled = json.createArrayNode();
            data.path("signals").forEach(signal -> {
                if (signal.path("enabled").asBoolean()) {
                    enabled.add(signal);
                    enabledIds.add(signal.path("id").asText());
                }
            });
            dictionary = enabled.toString();
        }
        try (var input = new ClassPathResource("analysis/response-schema.json").getInputStream()) {
            schema = json.readTree(input);
            var signalId = (com.fasterxml.jackson.databind.node.ObjectNode) schema.path("properties")
                    .path("signals").path("items").path("properties").path("id");
            signalId.set("enum", enabledIds);
            signalId.put("description", "신호 사전의 활성 ID를 그대로 사용한다. 예: S-A2. sig1 같은 근거 일련번호가 아니다.");
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