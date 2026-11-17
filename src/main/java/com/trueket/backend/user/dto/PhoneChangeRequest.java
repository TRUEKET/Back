package com.trueket.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record PhoneChangeRequest(@NotBlank @Schema(example="01012345678") String phone, @NotNull @Schema(description="번호 변경 인증 발급 ID", example="1") Long verificationId) {}
