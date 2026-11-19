package com.trueket.backend.analyze.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;

class SignalDictionaryV02Tests {
    @Test void definitionsExamplesAndExplicitDisabledPolicyAreLoaded() throws Exception {
        var dictionary=new SignalDictionary();
        assertEquals("v0.2",dictionary.version());
        var a=dictionary.enabledSignal("S-A1").orElseThrow();
        assertFalse(a.name().isBlank());assertFalse(a.definition().isBlank());
        assertTrue(a.examples().size()>=3);assertFalse(a.counterExamples().isEmpty());assertFalse(a.notSignal().isEmpty());
        assertTrue(dictionary.enabledSignal("S-F2").isEmpty());assertTrue(dictionary.enabledSignal("S-E3").isEmpty());
    }

    @Test void unknownMetadataIsIgnoredWithoutLosingKnownRules() throws Exception {
        var json=new ObjectMapper();
        var resource=json.readTree(getClass().getResourceAsStream("/analysis/signals.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)resource).put("editorNote","future metadata");
        ((com.fasterxml.jackson.databind.node.ObjectNode)resource.path("signals").get(0)).put("futureExplanation","note");
        var dictionary=new SignalDictionary(new ByteArrayInputStream(json.writeValueAsBytes(resource)));
        assertEquals("v0.2",dictionary.version());assertTrue(dictionary.enabledSignal("S-A1").isPresent());
        ((com.fasterxml.jackson.databind.node.ObjectNode)resource.path("signals").get(0)).put("criterion","INVALID");
        assertThrows(java.io.IOException.class,()->new SignalDictionary(new ByteArrayInputStream(json.writeValueAsBytes(resource))));
    }

    @Test void promptContainsFullActiveDefinitionsAndNotDisabledRules() throws Exception {
        var prompt=new AnalysisPrompt();String text=prompt.text("사용자 입력");
        int start=text.indexOf("<signal_dictionary>\n")+"<signal_dictionary>\n".length();
        var signals=new ObjectMapper().readTree(text.substring(start,text.indexOf("\n</signal_dictionary>")));
        for(var definition:signals) {
            assertTrue(definition.path("enabled").asBoolean());assertFalse(definition.path("definition").asText().isBlank());
            assertTrue(definition.has("notSignal"));assertTrue(definition.has("counterExamples"));
        }
        assertTrue(text.contains("반값 미만"));assertTrue(text.contains("거래 도중 처음 안내한 계좌"));
        assertTrue(text.contains("오른쪽 말풍선"));assertTrue(text.contains("더치트 조회해 보셔도 돼요"));
        var ids=prompt.schema().path("properties").path("signals").path("items").path("properties").path("id").path("enum");
        assertFalse(ids.toString().contains("S-F2"));assertFalse(ids.toString().contains("S-E3"));
    }
}
