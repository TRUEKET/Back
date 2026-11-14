package com.trueket.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PhoneChangeRequest(@NotBlank String phone, @NotNull Long verificationId) {}
