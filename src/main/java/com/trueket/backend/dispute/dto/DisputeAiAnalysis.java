package com.trueket.backend.dispute.dto;

import java.util.List;

public record DisputeAiAnalysis(
        String docType,

        String senderBank,
        String senderAccount,

        String receiverBank,
        String receiverAccount,

        boolean completed,
        boolean readable,
        boolean delivered,

        List<MaskBox> maskBoxes
) {
    public record MaskBox(
            double x1,
            double y1,
            double x2,
            double y2
    ) {}
}