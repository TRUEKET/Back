package com.trueket.backend.analyze.dto;

import java.util.List;

public record ConversationDetails(int imageCount, int suspiciousImageCount, List<ImageEvidence> images) {
    public ConversationDetails { images = List.copyOf(images); }

    public record ImageEvidence(int imageNumber, List<ChatAssessment.Evidence> countedSignals,
                                List<ChatAssessment.Evidence> informationalSignals) {
        public ImageEvidence {
            countedSignals = List.copyOf(countedSignals);
            informationalSignals = List.copyOf(informationalSignals);
        }
    }
}