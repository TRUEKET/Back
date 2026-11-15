package com.trueket.backend.user.dto;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import com.trueket.backend.user.entity.User;
import java.time.OffsetDateTime;
public final class AuthDtos {
    private AuthDtos() {}
    public record PhoneSend(@NotBlank @Schema(description="휴대폰 번호 문자열, 하이픈 없이 010으로 시작", example="01012345678") String phone) {}
    public record PhoneVerify(@NotNull @Schema(description="직전 발급 응답의 ID", example="1") Long verificationId, @NotBlank @Schema(description="휴대폰 번호 문자열, 하이픈 없이 010으로 시작", example="01012345678") String phone,
        @Pattern(regexp="[0-9]{6}", message="인증번호는 숫자 6자리여야 합니다.") @NotBlank @Schema(description="이번 발급 응답 demoSms.code 또는 실제 수신한 6자리 값. 고정 코드가 아닙니다.", example="482917") String code) {}
    public record Signup(
        @NotBlank @Email(message="형식이 올바르지 않습니다.") @Schema(example="fan@example.invalid") String email,
        @NotBlank @Size(max=72, message="비밀번호는 72자 이하여야 합니다.")
        @Pattern(regexp="(?=.*[A-Za-z])(?=.*[0-9]).{8,}", message="8자 이상, 영문과 숫자를 섞어 주세요") @Schema(description="영문·숫자 8자 이상, UTF-8 72바이트 이하", example="Examplepass123!", accessMode=Schema.AccessMode.WRITE_ONLY) String password,
        @NotBlank @Schema(description="휴대폰 번호 문자열, 하이픈 없이 010으로 시작", example="01012345678") String phone, @NotNull @Schema(description="직전 발급 응답의 ID", example="1") Long verificationId,
        @NotNull @AssertTrue(message="개인정보 정책에 동의해 주세요") @Schema(description="개인정보 동의 필수", example="true") Boolean privacyConsent) {}
    public record Login(@NotBlank @Email(message="형식이 올바르지 않습니다.") @Schema(example="fan@example.invalid") String email, @NotBlank @Schema(description="영문·숫자 8자 이상, UTF-8 72바이트 이하", example="Examplepass123!", accessMode=Schema.AccessMode.WRITE_ONLY) String password) {}
    public record PhoneSent(@Schema(description="직전 발급 응답의 ID", example="1") Long verificationId, OffsetDateTime expiresAt,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        DemoSms demoSms) {}
    public record DemoSms(String maskedPhone, @Schema(description="이번 발급 응답 demoSms.code 또는 실제 수신한 6자리 값. 고정 코드가 아닙니다.", example="482917") String code, String message) {}
    public record UserResponse(Long id, @Schema(example="fan@example.invalid") String email, @Schema(description="휴대폰 뒤 4자리", example="5678") String phoneLast4) {
        public static UserResponse from(User user) { return new UserResponse(user.getId(), user.getEmail(), user.getPhoneLast4()); }
    }
}
