package com.trueket.backend.trueshot;

import com.trueket.backend.trueshot.service.TrueshotMaskRenderer;
import com.trueket.backend.trueshot.dto.TrueshotMaskDtos.Box;
import com.trueket.backend.global.exception.ApiException;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TrueshotMaskRendererTests {
    @Test void additionalMaskPreservesAutomaticMask() throws Exception {
        var image=new BufferedImage(400,400,BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0,0,400,400); graphics.dispose();
        var output=new ByteArrayOutputStream(); ImageIO.write(image,"jpg",output);
        var renderer=new TrueshotMaskRenderer();
        byte[] automatic=renderer.render(output.toByteArray(),List.of(new Box(0.1,0.1,0.2,0.2)));
        byte[] additional=renderer.render(automatic,List.of(new Box(0.6,0.6,0.2,0.2)));
        var result=ImageIO.read(new ByteArrayInputStream(additional));
        assertTrue(new Color(result.getRGB(80,80)).getRed()<10);
        assertTrue(new Color(result.getRGB(280,280)).getRed()<10);
        assertTrue(new Color(result.getRGB(200,200)).getRed()>240);
        assertThrows(ApiException.class,()->renderer.render(automatic,List.of(new Box(0.9,0.9,0.5,0.5))));
    }
}
