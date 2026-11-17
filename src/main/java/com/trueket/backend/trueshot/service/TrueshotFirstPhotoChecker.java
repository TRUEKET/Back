package com.trueket.backend.trueshot.service;

import java.util.List;

public interface TrueshotFirstPhotoChecker {
    record Result(boolean codeVisible, boolean codeMatches, boolean clear) {
        public boolean passed() {
            return codeVisible && codeMatches && clear;
        }

        public List<String> retryReasons() {
            if (!clear) return List.of("사진이 흐려요. 다시 찍어 주세요");
            if (!codeVisible || !codeMatches) return List.of("인증코드가 잘 보이게 다시 찍어 주세요");
            return List.of();
        }
    }

    Result check(String code, TrueshotImageInput.Prepared photo);
}
