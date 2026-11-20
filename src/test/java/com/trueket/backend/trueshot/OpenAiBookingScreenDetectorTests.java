package com.trueket.backend.trueshot;

import com.trueket.backend.global.ai.ModelException;
import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.trueshot.service.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenAiBookingScreenDetectorTests {
    @Test void bothRoundsUseSameReadabilityCriterionForPaperTicketsAndComputerBookingHistory() {
        var model = mock(TrueshotOpenAiClient.class);
        var photo = new TrueshotImageInput.Prepared(new byte[]{1}, "image/jpeg");
        when(model.generate(anyString(), anyList(), any(), any())).thenReturn("""
            {"codeVisible":true,"codeMatches":true,"clear":true}
            """);
        assertTrue(new OpenAiTrueshotFirstPhotoChecker(model).check("AB12", photo).passed());
        var firstPrompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(model).generate(firstPrompt.capture(), anyList(), any(), any());
        clearInvocations(model);

        when(model.generate(anyString(), anyList(), any(), any())).thenReturn("""
            {"found":true,"certain":true,"clear":true,"x":0.3,"y":0.2,"width":0.5,"height":0.6,"text":"관람 공연 내역"}
            """);
        var detection = new OpenAiBookingScreenDetector(model).detect(photo);
        assertTrue(detection.found() && detection.certain() && detection.clear());
        var secondPrompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(model).generate(secondPrompt.capture(), anyList(), any(), any());
        String criterion = firstPrompt.getValue().substring(firstPrompt.getValue().indexOf("화질 clear는"),
                firstPrompt.getValue().indexOf("사진 속 다른 문구"));
        assertTrue(secondPrompt.getValue().contains(criterion));
        assertTrue(secondPrompt.getValue().contains("노트북·데스크톱 모니터"));
        assertTrue(secondPrompt.getValue().contains("마이페이지의 관람 공연 내역"));
        assertTrue(firstPrompt.getValue().contains("종이 티켓·실물 입장권·출력한 예매 내역"));
        assertTrue(secondPrompt.getValue().contains("종이와 기기 화면 모두 허용"));
        assertTrue(secondPrompt.getValue().contains("종이 티켓은 티켓 외곽"));
    }

    @Test void parsesScreenAndRejectsMalformedOrOutOfBoundsResponses() {
        var model = mock(TrueshotOpenAiClient.class);
        var detector = new OpenAiBookingScreenDetector(model);
        var photo = new TrueshotImageInput.Prepared(new byte[]{1},"image/jpeg");
        when(model.generate(anyString(),anyList(),any(),any())).thenReturn("""
            {"found":true,"certain":true,"clear":true,"x":0.1,"y":0.2,"width":0.5,"height":0.6,"text":"공연"}
            """);
        assertEquals("공연",detector.detect(photo).text());
        assertTrue(detector.detect(photo).clear());
        for (String response : new String[]{"null", "{}", "not json",
                "{\"found\":true,\"certain\":true,\"clear\":true,\"x\":0.9,\"y\":0,\"width\":0.5,\"height\":0.5,\"text\":\"\"}",
                "{\"found\":true,\"certain\":true,\"x\":0.1,\"y\":0.2,\"width\":0.5,\"height\":0.6,\"text\":\"공연\"}"}) {
            when(model.generate(anyString(),anyList(),any(),any())).thenReturn(response);
            assertThrows(ApiException.class,()->detector.detect(photo));
        }
        when(model.generate(anyString(),anyList(),any(),any())).thenThrow(new ModelException(ModelException.Kind.TIMEOUT));
        assertThrows(ApiException.class,()->detector.detect(photo));
    }
}
