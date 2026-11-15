package com.trueket.backend.trueshot;

import com.trueket.backend.global.ai.ModelException;
import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.trueshot.service.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenAiBookingScreenDetectorTests {
    @Test void parsesScreenAndRejectsMalformedOrOutOfBoundsResponses() {
        var model = mock(TrueshotOpenAiClient.class);
        var detector = new OpenAiBookingScreenDetector(model);
        var photo = new TrueshotImageInput.Prepared(new byte[]{1},"image/jpeg");
        when(model.generate(anyString(),anyList(),any(),any())).thenReturn("""
            {"found":true,"certain":true,"x":0.1,"y":0.2,"width":0.5,"height":0.6,"text":"공연"}
            """);
        assertEquals("공연",detector.detect(photo).text());
        for (String response : new String[]{"null", "{}", "not json",
                "{\"found\":true,\"certain\":true,\"x\":0.9,\"y\":0,\"width\":0.5,\"height\":0.5,\"text\":\"\"}"}) {
            when(model.generate(anyString(),anyList(),any(),any())).thenReturn(response);
            assertThrows(ApiException.class,()->detector.detect(photo));
        }
        when(model.generate(anyString(),anyList(),any(),any())).thenThrow(new ModelException(ModelException.Kind.TIMEOUT));
        assertThrows(ApiException.class,()->detector.detect(photo));
    }
}
