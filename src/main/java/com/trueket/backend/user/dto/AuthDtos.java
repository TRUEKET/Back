package com.trueket.backend.user.dto;
import jakarta.validation.constraints.*;
import com.trueket.backend.user.entity.User;
import java.time.OffsetDateTime;
public final class AuthDtos {
    private AuthDtos() {}
    public record PhoneSend(@NotBlank String phone) {}
    public record PhoneVerify(@NotNull Long verificationId, @NotBlank String phone,
        @Pattern(regexp="[0-9]{6}", message="인증번호는 숫자 6자리여야 합니다.") @NotBlank String code) {}
    public record Signup(
        @NotBlank @Email(message="형식이 올바르지 않습니다.") String email,
        @NotBlank @Size(max=72, message="비밀번호는 72자 이하여야 합니다.")
        @Pattern(regexp="(?=.*[A-Za-z])(?=.*[0-9]).{8,}", message="8자 이상, 영문과 숫자를 섞어 주세요") String password,
        @NotBlank String phone, @NotNull Long verificationId,
        @NotNull @AssertTrue(message="개인정보 정책에 동의해 주세요") Boolean privacyConsent) {}
    public record Login(@NotBlank @Email(message="형식이 올바르지 않습니다.") String email, @NotBlank String password) {}
    public record PhoneSent(Long verificationId, OffsetDateTime expiresAt,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        DemoSms demoSms) {}
    public record DemoSms(String maskedPhone, String code, String message) {}
    public record UserResponse(Long id, String email, String phoneLast4) {
        public static UserResponse from(User user) { return new UserResponse(user.getId(), user.getEmail(), user.getPhoneLast4()); }
    }
}
