package com.trueket.backend.trueshot;

import com.trueket.backend.trueshot.service.BookingScreenDetector;
import com.trueket.backend.global.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class HandAnalysisApiTests {
    @Autowired MockMvc mvc;
    @MockitoBean BookingScreenDetector screens;

    @Test void returnsEveryHandAgainstScreenEdgesInOriginalPixels() throws Exception {
        when(screens.detect(any())).thenAnswer(call -> {
            var prepared = call.getArgument(0, com.trueket.backend.trueshot.service.TrueshotImageInput.Prepared.class);
            var decoded = ImageIO.read(new java.io.ByteArrayInputStream(prepared.bytes()));
            org.junit.jupiter.api.Assertions.assertEquals(2400, decoded.getWidth());
            return new BookingScreenDetector.Detection(true, true, .3, .2, .4, .6, "공연 A");
        });
        // 모델 입력은 가로 4800px 원본을 축소하더라도 응답은 원본 좌표를 유지해야 한다.
        var data = """
            {"imageWidth":4800,"imageHeight":800,"hands":[
             {"x":300,"y":420,"gesture":"Victory","confidence":0.96},
             {"x":4000,"y":420,"gesture":"Thumb_Up","confidence":0.8},
             {"x":1440,"y":100,"gesture":"None","confidence":0.2},
             {"x":3360,"y":700,"gesture":"Open_Palm","confidence":0.9}]}
            """;
        mvc.perform(withCsrf(upload(photo(4800, 800, "png"), data)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("ANALYZED"))
                .andExpect(jsonPath("$.screen.x").value(1440.0))
                .andExpect(jsonPath("$.screen.width").value(1920.0))
                .andExpect(jsonPath("$.screen.text").value("공연 A"))
                .andExpect(jsonPath("$.hands[0].position").value("LEFT"))
                .andExpect(jsonPath("$.hands[1].position").value("RIGHT"))
                .andExpect(jsonPath("$.hands[2].position").value("OVERLAP"))
                .andExpect(jsonPath("$.hands[3].position").value("OVERLAP"));
    }

    @Test void noHandsSkipsModelButStillRequiresValidImage() throws Exception {
        mvc.perform(withCsrf(upload(photo(400,400,"jpg"), input("[]"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_HANDS"))
                .andExpect(jsonPath("$.hands").isEmpty());
        verifyNoInteractions(screens);
        mvc.perform(withCsrf(upload(new byte[]{1,2,3}, input("[]"))))
                .andExpect(status().isBadRequest());
    }

    @Test void missingAndUncertainScreensNeverInventPositions() throws Exception {
        when(screens.detect(any())).thenReturn(new BookingScreenDetector.Detection(false,false,0,0,0,0,""));
        mvc.perform(withCsrf(upload(photo(400,400,"png"), input(hand(20, .9)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCREEN_NOT_FOUND"))
                .andExpect(jsonPath("$.hands[0].position").value("UNKNOWN"));
        when(screens.detect(any())).thenReturn(new BookingScreenDetector.Detection(true,false,.2,.2,.5,.5,""));
        mvc.perform(withCsrf(upload(photo(400,400,"png"), input(hand(20,.9)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCREEN_UNCERTAIN"));
    }

    @Test void rejectsBadCoordinatesDimensionsNullsAndUnknownGesturesBeforeModel() throws Exception {
        var photo = photo(400,400,"png");
        for (var data : new String[]{input(hand(-1,.9)), input(hand(401,.9)), input(hand(20,1.1)),
                input(hand(20,.9)).replace("Victory", "V"), input("[null]"), input("null"),
                input("[{\"x\":20,\"y\":30,\"gesture\":\"Victory\"}]"),
                input(hand(20,.9)).replace("imageWidth\":400", "imageWidth\":500")}) {
            mvc.perform(withCsrf(upload(photo, data))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(screens);
    }

    @Test void rejectsExifAndOversizeFilesAndKeepsCsrfProtection() throws Exception {
        var original = photo(400,400,"jpg");
        var exif = new ByteArrayOutputStream();
        exif.write(original,0,2);
        exif.write(new byte[]{(byte)255,(byte)225,0,8,'E','x','i','f',0,0});
        exif.write(original,2,original.length-2);
        mvc.perform(withCsrf(upload(exif.toByteArray(), input(hand(20,.9)))))
                .andExpect(status().isBadRequest());
        var png = photo(400,400,"png");
        var pngExif = new ByteArrayOutputStream();
        pngExif.write(png,0,33); // PNG 시그니처와 IHDR 헤더
        var chunk = new java.io.DataOutputStream(pngExif);
        byte[] typeAndPayload = {'e','X','I','f','I','I',42,0,8,0,0,0};
        chunk.writeInt(8);
        chunk.write(typeAndPayload);
        var crc = new java.util.zip.CRC32();
        crc.update(typeAndPayload);
        chunk.writeInt((int)crc.getValue());
        pngExif.write(png,33,png.length-33);
        mvc.perform(withCsrf(upload(pngExif.toByteArray(), input(hand(20,.9)))))
                .andExpect(status().isBadRequest());
        mvc.perform(withCsrf(upload(new byte[5*1024*1024+1], input("[]"))))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(upload(original,input(hand(20,.9)))).andExpect(status().isForbidden());
        verifyNoInteractions(screens);
    }

    @Test void modelOutageAndInvalidBoundsReturn503() throws Exception {
        when(screens.detect(any())).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"분석 실패"));
        mvc.perform(withCsrf(upload(photo(400,400,"png"),input(hand(20,.9)))))
                .andExpect(status().isServiceUnavailable());
        reset(screens);
        when(screens.detect(any())).thenReturn(new BookingScreenDetector.Detection(true,true,.9,0,.5,.5,""));
        mvc.perform(withCsrf(upload(photo(400,400,"png"),input(hand(20,.9)))))
                .andExpect(status().isServiceUnavailable());
    }

    private String input(String hands) {
        return "{\"imageWidth\":400,\"imageHeight\":400,\"hands\":"+hands+"}";
    }
    private String hand(double x, double confidence) {
        return "[{\"x\":"+x+",\"y\":200,\"gesture\":\"Victory\",\"confidence\":"+confidence+"}]";
    }
    private byte[] photo(int width,int height,String format) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),format,output);
        return output.toByteArray();
    }
    private MockMultipartHttpServletRequestBuilder upload(byte[] photo, String data) {
        return multipart("/hand-analyze")
                .file(new MockMultipartFile("photo","photo.jpg","image/jpeg",photo))
                .file(new MockMultipartFile("analysis","analysis.json","application/json",data.getBytes(StandardCharsets.UTF_8)));
    }
    private MockMultipartHttpServletRequestBuilder withCsrf(MockMultipartHttpServletRequestBuilder request) throws Exception {
        var result = mvc.perform(get("/auth/csrf")).andReturn();
        var csrf = (CsrfToken) result.getRequest().getAttribute(CsrfToken.class.getName());
        request.session((MockHttpSession) result.getRequest().getSession(false));
        request.header(csrf.getHeaderName(),csrf.getToken());
        return request;
    }
}
