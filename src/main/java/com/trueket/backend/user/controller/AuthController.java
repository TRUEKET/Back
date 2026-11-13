package com.trueket.backend.user.controller;
import com.trueket.backend.user.dto.AuthDtos.*;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.user.repository.UserRepository;
import com.trueket.backend.user.service.AuthService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import com.trueket.backend.global.exception.ApiException;
import org.springframework.http.HttpStatus;
import java.util.List;
@RestController
public class AuthController {
    private final AuthService auth;
    private final UserRepository users;
    public AuthController(AuthService auth, UserRepository users) { this.auth=auth; this.users=users; }
    @GetMapping("/auth/csrf") public CsrfToken csrf(CsrfToken token) { return token; }
    @PostMapping("/auth/phone/send")
    public PhoneSent send(@Valid @RequestBody PhoneSend input, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        PhoneSent sent = auth.send(input);
        request.getSession().setAttribute("phoneVerificationId", sent.verificationId());
        request.getSession().removeAttribute("verifiedPhoneId");
        return sent;
    }

    @PostMapping("/auth/phone/verify")
    public void verify(@Valid @RequestBody PhoneVerify input, HttpServletRequest request) {
        requireVerificationSession(request, "phoneVerificationId", input.verificationId());
        auth.verify(input);
        request.getSession().setAttribute("verifiedPhoneId", input.verificationId());
    }
    @PostMapping("/auth/signup")
    public UserResponse signup(@Valid @RequestBody Signup input, HttpServletRequest request, HttpServletResponse response) {
        requireVerificationSession(request, "verifiedPhoneId", input.verificationId());
        User user = auth.signup(input);
        request.getSession().removeAttribute("phoneVerificationId");
        request.getSession().removeAttribute("verifiedPhoneId");
        return authenticate(user, request, response);
    }
    @PostMapping("/auth/login")
    public UserResponse login(@Valid @RequestBody Login input, HttpServletRequest request, HttpServletResponse response) {
        return authenticate(auth.login(input), request, response);
    }
    @GetMapping("/me") public UserResponse me(Authentication authentication) {
        User user=users.findById(Long.valueOf(authentication.getName()))
                .filter(value -> "ACTIVE".equals(value.getStatus()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        return UserResponse.from(user);
    }
    private void requireVerificationSession(HttpServletRequest request, String attribute, Long id) {
        HttpSession session = request.getSession(false);
        if (session == null || !id.equals(session.getAttribute(attribute))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "이 브라우저에서 휴대폰 인증을 진행해 주세요");
        }
    }

    private UserResponse authenticate(User user, HttpServletRequest request, HttpServletResponse response) {
        request.getSession(); request.changeSessionId();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.getId().toString(), null, List.of()));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        new HttpSessionCsrfTokenRepository().saveToken(null, request, response);
        return UserResponse.from(user);
    }
}