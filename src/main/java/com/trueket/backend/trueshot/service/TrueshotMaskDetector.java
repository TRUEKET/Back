package com.trueket.backend.trueshot.service;

import com.trueket.backend.trueshot.dto.TrueshotMaskDtos.Box;
import java.util.List;

public interface TrueshotMaskDetector {
    record Detection(boolean complete, List<Box> boxes) {}
    Detection detect(byte[] image);
}
