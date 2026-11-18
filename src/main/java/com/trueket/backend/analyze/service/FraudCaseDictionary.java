
package com.trueket.backend.analyze.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.analyze.dto.FraudCase;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class FraudCaseDictionary {

    private final Map<String, List<FraudCase>> cases;

    public FraudCaseDictionary() throws IOException {

        List<FraudCase> loadedCases;

        // 1. JSON 파일 읽기
        try (var stream = new ClassPathResource(
                "analysis/cases.json"
        ).getInputStream()) {

            loadedCases = new ObjectMapper().readValue(
                    stream,
                    new TypeReference<List<FraudCase>>() {}
            );
        }

        // 2. 신호 ID를 기준으로 사례 분류
        Map<String, List<FraudCase>> grouped =
                new LinkedHashMap<>();

        for (FraudCase fraudCase : loadedCases) {

            if (fraudCase.signalId() == null
                    || fraudCase.signalId().isBlank()) {

                throw new IllegalStateException(
                        "실제 사례의 신호 ID를 확인해 주세요."
                );
            }

            grouped.computeIfAbsent(
                    fraudCase.signalId(),
                    key -> new ArrayList<>()
            ).add(fraudCase);
        }

        // 3. 수정할 수 없는 데이터로 변환
        grouped.replaceAll(
                (key, value) -> List.copyOf(value)
        );

        this.cases = Map.copyOf(grouped);
    }

    // 4. 신호 ID에 해당하는 실제 사례 반환
    public List<FraudCase> findBySignalId(String signalId) {

        if (signalId == null) {
            return List.of();
        }

        return cases.getOrDefault(
                signalId,
                List.of()
        );
    }
}
