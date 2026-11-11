package com.trueket.backend.user.service;
import com.trueket.backend.user.dto.AuthDtos.*;
import com.trueket.backend.user.entity.*;
import com.trueket.backend.user.repository.*;
import com.trueket.backend.global.exception.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import java.time.OffsetDateTime;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
@Service
public class AuthService {
    private final UserRepository users;
    private final PhoneVerificationRepository verifications;
    private final PhonePrivacy privacy;
    private final PasswordEncoder passwords;
    private final SmsSender sms;
    private final boolean demo;
    private final String dummyHash;
    public AuthService(UserRepository users, PhoneVerificationRepository verifications, PhonePrivacy privacy,
                       PasswordEncoder passwords, SmsSender sms, Environment environment) {
        this.users=users; this.verifications=verifications; this.privacy=privacy;
        this.passwords=passwords; this.sms=sms; this.demo=environment.acceptsProfiles(Profiles.of("demo"));
        dummyHash=passwords.encode(UUID.randomUUID().toString());
    }
    @Transactional
    public PhoneSent send(PhoneSend input) {
        String phone=privacy.normalize(input.phone());
        String hash=privacy.hash(phone);
        if (users.existsByPhoneHash(hash)) throw error("이 번호로 이미 가입한 계정이 있어요");
        PhoneVerification verification=verifications.findByPhoneHashAndPurpose(hash, "SIGNUP").orElseGet(() -> PhoneVerification.create(hash));
        OffsetDateTime now=OffsetDateTime.now();
        if (verification.getSentAt()!=null && verification.getSentAt().plusSeconds(60).isAfter(now))
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "인증번호는 1분 뒤 다시 받아 주세요");
        String code=demo ? "123456" : String.format("%06d", new SecureRandom().nextInt(1000000));
        verification.issue(privacy.hash(hash+":"+code), now);
        verifications.saveAndFlush(verification);
        sms.send(phone, code);
        return new PhoneSent(verification.getId(), verification.getExpiresAt());
    }
    @Transactional(noRollbackFor=ApiException.class)
    public void verify(PhoneVerify input) {
        PhoneVerification verification=challenge(input.phone(), input.verificationId());
        if (verification.getAttempts()>=5) throw error("인증 시도 횟수를 초과했어요. 다시 받아 주세요");
        String hash=privacy.hash(verification.getPhoneHash()+":"+input.code());
        if (!java.security.MessageDigest.isEqual(hash.getBytes(StandardCharsets.UTF_8), verification.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            verification.fail(); throw error("인증번호가 일치하지 않습니다.");
        }
        verification.verify(OffsetDateTime.now());
    }
    @Transactional
    public User signup(Signup input) {
        if (input.password().getBytes(StandardCharsets.UTF_8).length>72) throw error("비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
        if (users.existsByEmail(input.email())) throw new ApiException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.");
        String phone=privacy.normalize(input.phone());
        PhoneVerification verification=challenge(phone, input.verificationId());
        if (verification.getVerifiedAt()==null) throw error("휴대폰 인증이 필요합니다.");
        if (users.existsByPhoneHash(verification.getPhoneHash())) throw error("이 번호로 이미 가입한 계정이 있어요");
        User user=User.create(input.email(), passwords.encode(input.password()), verification.getPhoneHash(),
            privacy.encrypt(phone), phone.substring(7), verification.getVerifiedAt());
        user.agreePrivacy(OffsetDateTime.now());
        users.saveAndFlush(user);
        verification.consume(OffsetDateTime.now());
        return user;
    }
    public User login(Login input) {
        User user=users.findByEmail(input.email()).orElse(null);
        boolean matches=passwords.matches(input.password(), user==null ? dummyHash : user.getPasswordHash());
        if (!matches || user==null || !"ACTIVE".equals(user.getStatus()))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않습니다.");
        return user;
    }
    private PhoneVerification challenge(String phone, Long id) {
        PhoneVerification verification=verifications.findByPhoneHashAndPurpose(privacy.hash(privacy.normalize(phone)), "SIGNUP")
            .orElseThrow(() -> error("인증번호를 먼저 받아 주세요"));
        if (!verification.getId().equals(id) || verification.getConsumedAt()!=null) throw error("유효하지 않은 인증 요청입니다.");
        if (!verification.getExpiresAt().isAfter(OffsetDateTime.now())) throw error("인증 시간이 지났어요. 다시 받아 주세요");
        return verification;
    }
    private ApiException error(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
}
