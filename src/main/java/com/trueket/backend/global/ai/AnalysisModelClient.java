package com.trueket.backend.global.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;

public interface AnalysisModelClient {
    record Image(String mimeType, String data) {}
    String generate(String prompt, List<Image> images, JsonNode schema, Duration timeout);
}
