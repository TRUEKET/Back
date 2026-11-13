package com.trueket.backend.trueshot;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.trueshot.service.TrueshotImageInput;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class TrueshotImageInputTests {
    private final TrueshotImageInput input=new TrueshotImageInput();

    @Test void decodesJpegAndPngIntoJpeg() throws Exception {
        for (String format:new String[]{"jpg","png"}) {
            var output=new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(400,400,BufferedImage.TYPE_INT_RGB),format,output);
            assertJpeg(output.toByteArray());
        }
    }

    @Test void rejectsOversizeAndDisallowedContentEvenWithJpegMimeType() throws Exception {
        var error=assertThrows(ApiException.class,()->input.prepare(new MockMultipartFile("photo","x.jpg",
                "image/jpeg",new byte[TrueshotImageInput.MAX_BYTES+1])));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE,error.getStatus());
        var output=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(400,400,BufferedImage.TYPE_INT_RGB),"gif",output);
        assertThrows(ApiException.class,()->input.prepare(new MockMultipartFile("photo","x.jpg",
                "image/jpeg",output.toByteArray())));
    }

    private void assertJpeg(byte[] bytes) throws Exception {
        var prepared=input.prepare(new MockMultipartFile("photo","upload.bin","application/octet-stream",bytes));
        assertEquals("image/jpeg",prepared.mimeType());
        assertEquals(0xff,prepared.bytes()[0]&0xff);
        assertEquals(0xd8,prepared.bytes()[1]&0xff);
        assertNotNull(ImageIO.read(new ByteArrayInputStream(prepared.bytes())));
    }
}
