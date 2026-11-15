package com.trueket.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PasswordChangeRequest(
        @NotBlank @Schema(example="Examplepass123!", accessMode=Schema.AccessMode.WRITE_ONLY) String currentPassword,
        @NotBlank
        @Size(max = 72, message = "비밀번호는 72자 이하여야 합니다.")
        @Pattern(regexp = "(?=.*[A-Za-z])(?=.*[0-9]).{8,}",
                message = "8자 이상, 영문과 숫자를 섞어 주세요")
        @Schema(example="Changedpass456!", accessMode=Schema.AccessMode.WRITE_ONLY) String newPassword,
        @NotBlank @Schema(description="새 비밀번호와 동일하게 입력", example="Changedpass456!", accessMode=Schema.AccessMode.WRITE_ONLY) String confirmPassword
) {}
