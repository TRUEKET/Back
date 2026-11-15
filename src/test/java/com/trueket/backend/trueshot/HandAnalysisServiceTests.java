package com.trueket.backend.trueshot;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.trueshot.dto.HandAnalysisDtos.*;
import com.trueket.backend.trueshot.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HandAnalysisServiceTests {
    private final BookingScreenDetector screens=mock(BookingScreenDetector.class);
    private final HandAnalysisService service=new HandAnalysisService(new TrueshotImageInput(),screens);

    @Test void returnsEveryHandAgainstScreenEdgesInOriginalPixels() throws Exception {
        when(screens.detect(any())).thenAnswer(call -> {
            var prepared=call.getArgument(0,TrueshotImageInput.Prepared.class);
            assertEquals(2400,ImageIO.read(new ByteArrayInputStream(prepared.bytes())).getWidth());
            return new BookingScreenDetector.Detection(true,true,.3,.2,.4,.6,"공연 A");
        });
        var input=new Input(4800,800,List.of(new Hand(300.0,420.0,"Victory",.96),
                new Hand(4000.0,420.0,"Thumb_Up",.8),new Hand(1440.0,100.0,"None",.2),
                new Hand(3360.0,700.0,"Open_Palm",.9)));
        var result=analyze(photo(4800,800),input);
        assertEquals(Status.ANALYZED,result.status());
        assertEquals(1440,result.screen().x());
        assertEquals(1920,result.screen().width());
        assertEquals("공연 A",result.screen().text());
        assertEquals(List.of(Position.LEFT,Position.RIGHT,Position.OVERLAP,Position.OVERLAP),
                result.hands().stream().map(HandResult::position).toList());
    }

    @Test void noHandsSkipsModelButStillRequiresValidImage() throws Exception {
        var input=new Input(400,400,List.of());
        assertEquals(Status.NO_HANDS,analyze(photo(400,400),input).status());
        assertThrows(ApiException.class,()->analyze(new byte[]{1,2,3},input));
        verifyNoInteractions(screens);
    }

    @Test void missingAndUncertainScreensNeverInventPositions() throws Exception {
        var photo=photo(400,400);
        when(screens.detect(any())).thenReturn(new BookingScreenDetector.Detection(false,false,0,0,0,0,""));
        var result=analyze(photo,input());
        assertEquals(Status.SCREEN_NOT_FOUND,result.status());
        assertEquals(Position.UNKNOWN,result.hands().get(0).position());
        assertNull(result.screen());
        when(screens.detect(any())).thenReturn(new BookingScreenDetector.Detection(true,false,.2,.2,.5,.5,""));
        assertEquals(Status.SCREEN_UNCERTAIN,analyze(photo,input()).status());
    }

    @Test void modelOutageAndInvalidBoundsReturn503() throws Exception {
        var photo=photo(400,400);
        when(screens.detect(any())).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"분석 실패"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ApiException.class,()->analyze(photo,input())).getStatus());
        reset(screens);
        when(screens.detect(any())).thenReturn(new BookingScreenDetector.Detection(true,true,.9,0,.5,.5,""));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ApiException.class,()->analyze(photo,input())).getStatus());
    }

    private Input input() { return new Input(400,400,List.of(new Hand(20.0,200.0,"Victory",.9))); }
    private Output analyze(byte[] bytes,Input input) {
        var prepared=service.prepare(new MockMultipartFile("photo","photo.jpg","image/jpeg",bytes),input);
        return service.analyze(prepared,input,null);
    }
    private byte[] photo(int width,int height) throws Exception {
        var output=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),"jpg",output);
        return output.toByteArray();
    }
}
