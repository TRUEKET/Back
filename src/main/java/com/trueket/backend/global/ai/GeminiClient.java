package com.trueket.backend.global.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.trueket.backend.global.ai.ModelException.Kind.*;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="analysis.ai-provider", havingValue="gemini", matchIfMissing=true)
public class GeminiClient implements AnalysisModelClient {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String apiKey;
    private final String model;
    private final URI endpoint;
    private final String thinkingLevel;

    public GeminiClient(@Value("${gemini.api-key:}") String apiKey,
                        @Value("${gemini.model:gemini-3.5-flash-lite}") String model,
                        @Value("${gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
                        @Value("${gemini.thinking-level:low}") String thinkingLevel) {
        this.thinkingLevel = thinkingLevel;
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = URI.create(baseUrl + "/v1beta/interactions");
    }

    public String generate(String prompt, List<Image> images, JsonNode schema, Duration timeout) {
        if (apiKey.isBlank() || !java.util.Set.of("minimal","low","medium","high").contains(thinkingLevel)) {
            throw new ModelException(CONFIGURATION);
        }
        List<Map<String, Object>> input = new ArrayList<>();
        input.add(Map.of("type", "text", "text", prompt));
        for (Image image : images) {
            input.add(Map.of("type", "image", "mime_type", image.mimeType(), "data", image.data()));
        }
        try {
            String body = json.writeValueAsString(Map.of(
                    "model", model, "store", false, "input", input,
                    "generation_config", Map.of("thinking_level", thinkingLevel),
                    "response_format", Map.of("type", "text", "mime_type", "application/json", "schema", schema)));
            if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 19_000_000) {
                throw new ModelException(INVALID_RESPONSE);
            }
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("x-goog-api-key", apiKey).header("Content-Type", "application/json")
                    .header("Api-Revision", "2026-05-20")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new ModelException(AUTHENTICATION);
            }
            if (response.statusCode() == 429) {
                throw new ModelException(RATE_LIMIT);
            }
            if (response.statusCode() >= 500) {
                throw new ModelException(UPSTREAM);
            }
            if (response.statusCode() != 200 || response.body().length() > 2_000_000) {
                throw new ModelException(INVALID_RESPONSE);
            }
            JsonNode result = json.readTree(response.body());
            if (!"completed".equals(result.path("status").asText())) {
                throw new ModelException(INVALID_RESPONSE);
            }
            StringBuilder output = new StringBuilder();
            for (JsonNode step : result.path("steps")) {
                if ("model_output".equals(step.path("type").asText())) {
                    for (JsonNode part : step.path("content")) {
                        if ("text".equals(part.path("type").asText())) {
                            output.append(part.path("text").asText());
                        }
                    }
                }
            }
            if (output.isEmpty()) {
                throw new ModelException(INVALID_RESPONSE);
            }
            return output.toString();
        } catch (HttpTimeoutException e) {
            throw new ModelException(TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException(UPSTREAM);
        } catch (IOException e) {
            throw new ModelException(UPSTREAM);
        }
    }
}
