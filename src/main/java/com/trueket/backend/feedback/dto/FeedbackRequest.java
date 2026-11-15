package com.trueket.backend.feedback.dto;

import com.trueket.backend.feedback.entity.FeedbackKind;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;

public record FeedbackRequest(@NotNull(message = "피드백 종류를 선택해 주세요") @Schema(description="도움됨 또는 잘못된 판독", example="HELPFUL") FeedbackKind kind) {}
