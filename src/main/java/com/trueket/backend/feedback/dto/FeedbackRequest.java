package com.trueket.backend.feedback.dto;

import com.trueket.backend.feedback.entity.FeedbackKind;
import jakarta.validation.constraints.NotNull;

public record FeedbackRequest(@NotNull(message = "피드백 종류를 선택해 주세요") FeedbackKind kind) {}
