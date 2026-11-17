package com.trueket.backend.trueshot.service;

public interface BookingScreenDetector {
    // 화면 영역 좌표는 탐지기에 전달한 이미지의 크기를 기준으로 0~1 사이로 정규화한다.
    record Detection(boolean found, boolean certain, double x, double y,
                     double width, double height, String text, boolean clear) {
        public boolean validBounds() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(width)
                    && Double.isFinite(height) && x >= 0 && y >= 0 && width > 0 && height > 0
                    && x + width <= 1 && y + height <= 1;
        }
    }

    Detection detect(TrueshotImageInput.Prepared photo);
}
