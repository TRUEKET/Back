package com.trueket.backend.trueshot;

import com.trueket.backend.trueshot.service.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenAiTrueshotMaskDetectorTests {
    @Test void emptyRegionsAreCompleteOnlyWhenModelExplicitlyConfirms() {
        var model=mock(TrueshotOpenAiClient.class);
        var detector=new OpenAiTrueshotMaskDetector(model);
        when(model.generate(anyString(),anyList(),any(),any()))
                .thenReturn("{\"complete\":true,\"boxes\":[]}");
        var result=detector.detect(new byte[]{1});
        assertTrue(result.complete());
        assertTrue(result.boxes().isEmpty());
        for (String response: new String[]{"{\"complete\":false,\"boxes\":[]}","{}","null",
                "{\"complete\":true,\"boxes\":[{\"x\":0.9,\"y\":0,\"width\":0.5,\"height\":0.1}]}"}) {
            when(model.generate(anyString(),anyList(),any(),any())).thenReturn(response);
            assertFalse(detector.detect(new byte[]{1}).complete());
        }
    }
}
