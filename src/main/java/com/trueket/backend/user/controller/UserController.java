package com.trueket.backend.user.controller;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.user.dto.AccountResponse;
import com.trueket.backend.user.dto.AuthDtos.PhoneSend;
import com.trueket.backend.user.dto.AuthDtos.PhoneSent;
import com.trueket.backend.user.dto.AuthDtos.PhoneVerify;
import com.trueket.backend.user.dto.AuthDtos.UserResponse;
import com.trueket.backend.user.dto.PasswordChangeRequest;
import com.trueket.backend.user.dto.PhoneChangeRequest;
import com.trueket.backend.user.service.AuthService;
import com.trueket.backend.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/me")
public class UserController {
    private static final String PHONE_REQUEST = "phoneChangeRequest";
    private static final String PHONE_VERIFIED = "phoneChangeVerified";
    private final UserService users;
    private final AuthService auth;
    private final com.trueket.backend.user.service.WithdrawalService withdrawal;
    private final com.trueket.backend.analyze.service.AnalysisSessionStore analyses;

    public UserController(UserService users, AuthService auth,
            com.trueket.backend.user.service.WithdrawalService withdrawal,
            com.trueket.backend.analyze.service.AnalysisSessionStore analyses) {
        this.users = users;
        this.auth = auth;
        this.withdrawal = withdrawal;
        this.analyses = analyses;
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(Authentication authentication, HttpServletRequest request,
                         jakarta.servlet.http.HttpServletResponse response) {
        withdrawal.withdraw(userId(authentication));
        var session = request.getSession(false);
        if (session != null && session.getAttribute("analysisOwner") instanceof java.util.UUID owner) {
            analyses.deleteByOwner(owner);
        }
        new org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler()
            .logout(request,response,authentication);
        new org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler("JSESSIONID")
            .logout(request,response,authentication);
        response.setHeader("Cache-Control","no-store");
    }

    @GetMapping("/account")
    public ResponseEntity<AccountResponse> account(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(users.account(userId(authentication)));
    }

    @PostMapping("/phone/send")
    public PhoneSent sendPhone(Authentication authentication, @Valid @RequestBody PhoneSend input,
                               HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        Long id = userId(authentication);
        PhoneSent sent = auth.sendPhoneChange(id, input);
        request.getSession().setAttribute(PHONE_REQUEST, new PhoneChangeSession(id, sent.verificationId(), sent.expiresAt()));
        request.getSession().removeAttribute(PHONE_VERIFIED);
        return sent;
    }

    @PostMapping("/phone/verify")
    public Map<String, String> verifyPhone(Authentication authentication, @Valid @RequestBody PhoneVerify input,
                                           HttpServletRequest request) {
        Long id = userId(authentication);
        PhoneChangeSession verification = requirePhoneSession(request, PHONE_REQUEST, id, input.verificationId());
        auth.verifyPhoneChange(id, input, verification.expiresAt());
        request.getSession().setAttribute(PHONE_VERIFIED, verification);
        return Map.of("message", "인증이 완료되었습니다.");
    }

    @PatchMapping("/phone")
    public UserResponse changePhone(Authentication authentication, @Valid @RequestBody PhoneChangeRequest input,
                                    HttpServletRequest request) {
        Long id = userId(authentication);
        PhoneChangeSession verification = requirePhoneSession(request, PHONE_VERIFIED, id, input.verificationId());
        UserResponse user = users.changePhone(id, input, verification.expiresAt());
        request.getSession().removeAttribute(PHONE_REQUEST);
        request.getSession().removeAttribute(PHONE_VERIFIED);
        return user;
    }

    @PatchMapping("/password")
    public Map<String, String> changePassword(Authentication authentication,
                                              @Valid @RequestBody PasswordChangeRequest input) {
        users.changePassword(userId(authentication), input);
        return Map.of("message", "비밀번호를 바꿨어요");
    }

    private Long userId(Authentication authentication) {
        return Long.valueOf(authentication.getName());
    }

    private PhoneChangeSession requirePhoneSession(HttpServletRequest request, String attribute, Long userId, Long verificationId) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(attribute);
        if (!(value instanceof PhoneChangeSession verification)
                || !verification.userId().equals(userId) || !verification.verificationId().equals(verificationId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "이 브라우저에서 새 번호를 인증해 주세요");
        }
        return verification;
    }

    private record PhoneChangeSession(Long userId, Long verificationId, OffsetDateTime expiresAt) implements Serializable {}
}
