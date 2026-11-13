package com.trueket.backend.user.service;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.user.dto.AuthDtos.*;
import com.trueket.backend.user.dto.PhoneChangeRequest;
import com.trueket.backend.user.entity.PhoneVerification;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.user.repository.PhoneVerificationRepository;
import com.trueket.backend.user.repository.UserRepository;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class AuthService {
    private static final String SIGNUP = "SIGNUP";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserRepository users;
    private final PhoneVerificationRepository verifications;
    private final PhonePrivacy privacy;
    private final PasswordEncoder passwords;
    private final SmsSender sms;
    private final boolean demo;
    private final String dummyHash;

    public AuthService(UserRepository users, PhoneVerificationRepository verifications, PhonePrivacy privacy,
                       PasswordEncoder passwords, SmsSender sms, Environment environment) {
        this.users = users;
        this.verifications = verifications;
        this.privacy = privacy;
        this.passwords = passwords;
        this.sms = sms;
        this.demo = environment.acceptsProfiles(Profiles.of("demo"));
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public PhoneSent send(PhoneSend input) {
        return send(input, SIGNUP, null);
    }

    @Transactional
    public PhoneSent sendPhoneChange(Long userId, PhoneSend input) {
        requireActiveUser(userId);
        return send(input, phoneChangePurpose(userId), userId);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void verify(PhoneVerify input) {
        verify(input, SIGNUP, null);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void verifyPhoneChange(Long userId, PhoneVerify input, OffsetDateTime expectedExpiry) {
        requireActiveUser(userId);
        verify(input, phoneChangePurpose(userId), expectedExpiry);
    }

    @Transactional
    public OffsetDateTime consumePhoneChange(Long userId, PhoneChangeRequest input, OffsetDateTime expectedExpiry) {
        PhoneVerification verification = challenge(input.phone(), input.verificationId(),
                phoneChangePurpose(userId), expectedExpiry);
        if (verification.getVerifiedAt() == null) {
            throw error("휴대폰 인증이 필요합니다.");
        }
        verification.consume(OffsetDateTime.now());
        return verification.getVerifiedAt();
    }

    @Transactional
    public User signup(Signup input) {
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw error("비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
        if (users.existsByEmail(input.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.");
        }
        String phone = privacy.normalize(input.phone());
        PhoneVerification verification = challenge(phone, input.verificationId(), SIGNUP, null);
        if (verification.getVerifiedAt() == null) {
            throw error("휴대폰 인증이 필요합니다.");
        }
        if (users.existsByPhoneHash(verification.getPhoneHash())) {
            throw error("이 번호로 이미 가입한 계정이 있어요");
        }
        User user = User.create(input.email(), passwords.encode(input.password()), verification.getPhoneHash(),
                privacy.encrypt(phone), phone.substring(7), verification.getVerifiedAt());
        user.agreePrivacy(OffsetDateTime.now());
        users.saveAndFlush(user);
        verification.consume(OffsetDateTime.now());
        return user;
    }

    public User login(Login input) {
        User user = users.findByEmail(input.email()).orElse(null);
        boolean matches = passwords.matches(input.password(), user == null ? dummyHash : user.getPasswordHash());
        if (!matches || user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않습니다.");
        }
        return user;
    }

    private PhoneSent send(PhoneSend input, String purpose, Long excludedUserId) {
        String phone = privacy.normalize(input.phone());
        String hash = privacy.hash(phone);
        boolean duplicate = excludedUserId == null
                ? users.existsByPhoneHash(hash)
                : users.existsByPhoneHashAndIdNot(hash, excludedUserId);
        if (duplicate) {
            throw error(excludedUserId == null ? "이 번호로 이미 가입한 계정이 있어요" : "다른 계정에 등록된 번호예요");
        }
        PhoneVerification verification = verifications.findByPhoneHashAndPurpose(hash, purpose)
                .orElseGet(() -> PhoneVerification.create(hash, purpose));
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        if (verification.getSentAt() != null && verification.getSentAt().plusSeconds(60).isAfter(now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "인증번호는 1분 뒤 다시 받아 주세요");
        }
        String code;
        do {
            code = String.format(java.util.Locale.ROOT, "%06d", RANDOM.nextInt(1000000));
        } while (codeHash(verification, code).equals(verification.getCodeHash()));
        verification.issue(codeHash(verification, code), now);
        verifications.saveAndFlush(verification);
        sms.send(phone, code);
        DemoSms receipt = demo ? new DemoSms("010-****-" + phone.substring(7), code,
                "시연용 문자 수신함입니다. 실제 문자는 발송되지 않으며 휴대폰 소유 여부를 확인하지 않습니다.") : null;
        return new PhoneSent(verification.getId(), verification.getExpiresAt(), receipt);
    }

    private void verify(PhoneVerify input, String purpose, OffsetDateTime expectedExpiry) {
        PhoneVerification verification = challenge(input.phone(), input.verificationId(), purpose, expectedExpiry);
        if (verification.getAttempts() >= 5) {
            throw error("인증 시도 횟수를 초과했어요. 다시 받아 주세요");
        }
        String hash = codeHash(verification, input.code());
        if (!MessageDigest.isEqual(hash.getBytes(StandardCharsets.UTF_8),
                verification.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            verification.fail();
            throw error("인증번호가 일치하지 않습니다.");
        }
        verification.verify(OffsetDateTime.now());
    }

    private PhoneVerification challenge(String phone, Long id, String purpose, OffsetDateTime expectedExpiry) {
        PhoneVerification verification = verifications.findByPhoneHashAndPurpose(
                        privacy.hash(privacy.normalize(phone)), purpose)
                .orElseThrow(() -> error("인증번호를 먼저 받아 주세요"));
        if (!verification.getId().equals(id) || verification.getConsumedAt() != null) {
            throw error("유효하지 않은 인증 요청입니다.");
        }
        if (!verification.getExpiresAt().isAfter(OffsetDateTime.now())) {
            throw error("인증 시간이 지났어요. 다시 받아 주세요");
        }
        if (expectedExpiry != null && !expectedExpiry.isEqual(verification.getExpiresAt())) {
            throw error("다시 발급된 인증번호로 인증해 주세요");
        }
        return verification;
    }

    private void requireActiveUser(Long userId) {
        users.findById(userId).filter(user -> "ACTIVE".equals(user.getStatus()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
    }

    private String phoneChangePurpose(Long userId) {
        return "PHONE_CHANGE:" + userId;
    }

    private String codeHash(PhoneVerification verification, String code) {
        return privacy.hash(verification.getPurpose() + ":" + verification.getPhoneHash() + ":" + code);
    }

    private ApiException error(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}