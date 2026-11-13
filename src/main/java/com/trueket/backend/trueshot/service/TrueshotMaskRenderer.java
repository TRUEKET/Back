package com.trueket.backend.trueshot.service;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.trueshot.dto.TrueshotMaskDtos.Box;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.io.*;
import java.util.List;

@Component
public class TrueshotMaskRenderer {
    public byte[] render(byte[] source,List<Box> boxes) {
        try {
            var image=ImageIO.read(new ByteArrayInputStream(source));
            if (image==null || (long)image.getWidth()*image.getHeight()>40_000_000) throw invalid();
            var graphics=image.createGraphics();
            try {
                graphics.setColor(Color.BLACK);
                for (Box box:boxes) {
                    if (box==null || !box.valid()) throw invalid();
                    // 경계 문자가 드러나지 않도록 탐지 사각형 주위를 넓혀 불투명하게 덮는다.
                    int pad=Math.max(4,(int)(Math.max(image.getWidth(),image.getHeight())*0.01));
                    int x=Math.max(0,(int)Math.floor(box.x()*image.getWidth())-pad);
                    int y=Math.max(0,(int)Math.floor(box.y()*image.getHeight())-pad);
                    int right=Math.min(image.getWidth(),(int)Math.ceil((box.x()+box.width())*image.getWidth())+pad);
                    int bottom=Math.min(image.getHeight(),(int)Math.ceil((box.y()+box.height())*image.getHeight())+pad);
                    graphics.fillRect(x,y,right-x,bottom-y);
                }
            } finally { graphics.dispose(); }
            var output=new ByteArrayOutputStream();
            ImageIO.write(image,"jpg",output);
            if (output.size()>TrueshotImageInput.MAX_BYTES) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"가린 사진은 5MB 이하로 저장해야 합니다.");
            }
            return output.toByteArray();
        } catch (IOException e) { throw invalid(); }
    }
    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST,"가릴 사진이나 영역을 확인해 주세요."); }
}
