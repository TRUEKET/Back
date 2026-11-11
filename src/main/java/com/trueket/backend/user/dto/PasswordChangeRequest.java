package com.trueket.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PasswordChangeRequest(
        @NotBlank String currentPassword,
        @NotBlank
        @Size(max = 72, message = "비밀번호는 72자 이하여야 합니다.")
        @Pattern(regexp = "(?=.*[A-Za-z])(?=.*[0-9]).{8,}",
                message = "8자 이상, 영문과 숫자를 섞어 주세요")
        String newPassword,
        @NotBlank String confirmPassword
) {}
