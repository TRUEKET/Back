package com.trueket.backend.trueshot.service;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.trueshot.dto.HandAnalysisDtos.*;
import com.trueket.backend.trueshot.dto.SecondMission;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.IOException;
import java.util.Set;

@Service
public class HandAnalysisService {
    private static final Set<String> GESTURES = Set.of("None", "Closed_Fist", "Open_Palm", "Pointing_Up",
            "Thumb_Down", "Thumb_Up", "Victory", "ILoveYou");
    private final TrueshotImageInput images;
    private final BookingScreenDetector screens;

    public HandAnalysisService(TrueshotImageInput images, BookingScreenDetector screens) {
        this.images = images;
        this.screens = screens;
    }

    public TrueshotImageInput.Prepared prepare(MultipartFile photo, Input input) {
        validateHands(input);
        // 모델 입력용 이미지를 축소하기 전에 원본 이미지 크기를 검증한다.
        validateImage(photo, input);
        return images.prepare(photo);
    }

    // 저장한 원본으로 재시도할 때도 최초 업로드 이미지의 좌표계를 그대로 사용한다.
    public Output analyze(TrueshotImageInput.Prepared prepared, Input input, SecondMission mission) {
        if (input.hands().isEmpty()) return HandMissionEvaluator.evaluate(input, Status.NO_HANDS, null, mission);
        var detection = screens.detect(prepared);
        // 예매 화면을 특정하지 못한 것은 화질이 나쁘다는 관측과 다르다.
        // 이 경우 화질은 미확인(null)으로 두고 화면 탐지 실패 사유만 반환한다.
        if (!detection.found()) return HandMissionEvaluator.evaluate(input, Status.SCREEN_NOT_FOUND, null, mission);
        if (!detection.certain()) return HandMissionEvaluator.evaluate(input, Status.SCREEN_UNCERTAIN, null, mission);
        if (!detection.validBounds()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "예매 화면 좌표를 확인하지 못했어요. 다시 시도해 주세요.");
        }
        var screen = new Screen(detection.x() * input.imageWidth(), detection.y() * input.imageHeight(),
                detection.width() * input.imageWidth(), detection.height() * input.imageHeight(), detection.text());
        return HandMissionEvaluator.evaluate(input, Status.ANALYZED, screen, mission).withQuality(detection.clear());
    }

    private void validateHands(Input input) {
        for (Hand hand : input.hands()) {
            if (!Double.isFinite(hand.x()) || !Double.isFinite(hand.y())
                    || hand.x() < 0 || hand.x() > input.imageWidth()
                    || hand.y() < 0 || hand.y() > input.imageHeight()
                    || !Double.isFinite(hand.confidence()) || hand.confidence() < 0 || hand.confidence() > 1
                    || !GESTURES.contains(hand.gesture())) {
                throw invalid("손 좌표·동작·신뢰도를 확인해 주세요.");
            }
        }
    }

    private void validateImage(MultipartFile photo, Input input) {
        if (photo == null || photo.isEmpty()) throw invalid("사진을 올려 주세요.");
        if (photo.getSize() > TrueshotImageInput.MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "사진은 5MB 이하로 올려 주세요.");
        }
        try (var source = photo.getInputStream(); var stream = new MemoryCacheImageInputStream(source)) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalid("JPEG 또는 PNG 사진을 올려 주세요.");
            var reader = readers.next();
            try {
                reader.setInput(stream);
                if (reader.getWidth(0) != input.imageWidth() || reader.getHeight(0) != input.imageHeight()) {
                    throw invalid("이미지 크기는 실제 업로드 파일과 같아야 합니다.");
                }
            } finally { reader.dispose(); }
            // canvas로 다시 저장하면 EXIF가 제거된다. 이미지에만 회전을 적용해 손 좌표와 어긋나는 것을
            // 방지하기 위해 EXIF가 남아 있는 JPEG·PNG 파일은 거부한다.
            byte[] bytes = photo.getBytes();
            if (hasJpegExif(bytes) || hasPngExif(bytes)) {
                throw invalid("사진 회전을 적용한 뒤 canvas로 다시 저장해 보내 주세요.");
            }
        } catch (IOException e) {
            throw invalid("사진을 읽지 못했어요. 다시 올려 주세요.");
        }
    }

    private boolean hasJpegExif(byte[] data) {
        if (data.length < 4 || (data[0] & 255) != 255 || (data[1] & 255) != 216) return false;
        int offset = 2;
        while (offset + 3 < data.length) {
            if ((data[offset++] & 255) != 255) break;
            while (offset < data.length && (data[offset] & 255) == 255) offset++;
            if (offset >= data.length) break;
            int marker = data[offset++] & 255;
            if (marker == 218 || marker == 217) break;
            if (marker == 1 || marker >= 208 && marker <= 215) continue;
            if (offset + 1 >= data.length) break;
            int length = ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
            if (length < 2 || length > data.length - offset) break;
            if (marker == 225 && length >= 8 && data[offset + 2] == 'E' && data[offset + 3] == 'x'
                    && data[offset + 4] == 'i' && data[offset + 5] == 'f'
                    && data[offset + 6] == 0 && data[offset + 7] == 0) return true;
            offset += length;
        }
        return false;
    }

    private boolean hasPngExif(byte[] data) {
        byte[] signature = {(byte)137,80,78,71,13,10,26,10};
        if (data.length < 8) return false;
        for (int i = 0; i < 8; i++) if (data[i] != signature[i]) return false;
        int offset = 8;
        while (offset <= data.length - 12) {
            long length = ((long)(data[offset] & 255) << 24) | ((long)(data[offset+1] & 255) << 16)
                    | ((data[offset+2] & 255) << 8) | (data[offset+3] & 255);
            if (length > data.length - offset - 12) break;
            if (data[offset+4] == 'e' && data[offset+5] == 'X'
                    && data[offset+6] == 'I' && data[offset+7] == 'f') return true;
            offset += (int)length + 12;
        }
        return false;
    }

    private ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
}
