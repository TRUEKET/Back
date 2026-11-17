package com.trueket.backend.user.controller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

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

@Tag(name="계정")
@SecurityRequirement(name="sessionAuth")
@ApiResponses({
    @ApiResponse(responseCode="400", description="입력 형식·검증 조건을 확인해 주세요.", content=@io.swagger.v3.oas.annotations.media.Content),
    @ApiResponse(responseCode="403", description="CSRF 토큰 누락 또는 현재 세션과 불일치", content=@io.swagger.v3.oas.annotations.media.Content),
    @ApiResponse(responseCode="500", description="서버 내부 오류. 응답과 서버 로그를 확인하세요.", content=@io.swagger.v3.oas.annotations.media.Content)
})
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

    @Operation(summary="회원 탈퇴", description="확인 후 호출하세요. 요청 본문·사용자 ID를 받지 않습니다. 본인 계정과 연관 신고·사진을 삭제하고 현재 세션을 종료합니다. 사진 삭제 실패 시 503으로 중단하며 재시도할 수 있습니다. 다른 사용자 기록과 공유 식별자는 보존합니다.", parameters=@Parameter(name="X-CSRF-TOKEN", in=ParameterIn.HEADER, required=true, schema=@io.swagger.v3.oas.annotations.media.Schema(type="string"), description="같은 브라우저에서 GET /auth/csrf로 받은 token. 가입·로그인 후 다시 발급하세요."))
    @ApiResponses({@ApiResponse(responseCode="204", description="탈퇴 완료, 본문 없음"),@ApiResponse(responseCode="401", description="로그인 필요", content=@io.swagger.v3.oas.annotations.media.Content),@ApiResponse(responseCode="409", description="다른 계정과 공유된 파일 참조", content=@io.swagger.v3.oas.annotations.media.Content),@ApiResponse(responseCode="503", description="사진 삭제 또는 DB 처리 실패", content=@io.swagger.v3.oas.annotations.media.Content)})
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

    @Operation(summary="내 계정 상세", description="로그인 사용자 계정 설정 화면에서 호출합니다. 반환 항목은 AccountResponse를 확인하세요.")
    @ApiResponses({@ApiResponse(responseCode="200", description="계정 상세"),@ApiResponse(responseCode="401", description="로그인 필요", content=@io.swagger.v3.oas.annotations.media.Content)})
    @GetMapping("/account")
    public ResponseEntity<AccountResponse> account(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(users.account(userId(authentication)));
    }

    @Operation(summary="번호 변경용 인증번호 발급", description="가입용 인증과 별개입니다. 로그인 사용자·현재 세션에 연결됩니다. demo에서는 실제 SMS를 발송하지 않습니다.", parameters=@Parameter(name="X-CSRF-TOKEN", in=ParameterIn.HEADER, required=true, schema=@io.swagger.v3.oas.annotations.media.Schema(type="string"), description="같은 브라우저에서 GET /auth/csrf로 받은 token. 가입·로그인 후 다시 발급하세요."))
    @ApiResponses({@ApiResponse(responseCode="200", description="새 번호 인증 정보"),@ApiResponse(responseCode="401", description="로그인 필요", content=@io.swagger.v3.oas.annotations.media.Content),@ApiResponse(responseCode="429", description="재발급 간격 제한", content=@io.swagger.v3.oas.annotations.media.Content)})
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

    @Operation(summary="번호 변경용 인증번호 확인", description="이 세션에서 발급받은 verificationId·새 번호·코드를 보냅니다. 확인만으로 번호가 바뀌지 않고 PATCH /me/phone이 필요합니다.", parameters=@Parameter(name="X-CSRF-TOKEN", in=ParameterIn.HEADER, required=true, schema=@io.swagger.v3.oas.annotations.media.Schema(type="string"), description="같은 브라우저에서 GET /auth/csrf로 받은 token. 가입·로그인 후 다시 발급하세요."))
    @ApiResponses({@ApiResponse(responseCode="200", description="인증 확인"),@ApiResponse(responseCode="401", description="로그인 필요", content=@io.swagger.v3.oas.annotations.media.Content)})
    @PostMapping("/phone/verify")
    public Map<String, String> verifyPhone(Authentication authentication, @Valid @RequestBody PhoneVerify input,
                                           HttpServletRequest request) {
        Long id = userId(authentication);
        PhoneChangeSession verification = requirePhoneSession(request, PHONE_REQUEST, id, input.verificationId());
        auth.verifyPhoneChange(id, input, verification.expiresAt());
        request.getSession().setAttribute(PHONE_VERIFIED, verification);
        return Map.of("message", "인증이 완료되었습니다.");
    }

    @Operation(summary="인증한 새 휴대폰 번호 저장", description="send → verify → 저장 순서입니다. 같은 사용자·세션·발급 건이어야 하고 인증은 재사용할 수 없습니다.", parameters=@Parameter(name="X-CSRF-TOKEN", in=ParameterIn.HEADER, required=true, schema=@io.swagger.v3.oas.annotations.media.Schema(type="string"), description="같은 브라우저에서 GET /auth/csrf로 받은 token. 가입·로그인 후 다시 발급하세요."))
    @ApiResponses({@ApiResponse(responseCode="200", description="변경된 사용자 정보"),@ApiResponse(responseCode="401", description="로그인 필요", content=@io.swagger.v3.oas.annotations.media.Content),@ApiResponse(responseCode="409", description="이미 사용하는 번호", content=@io.swagger.v3.oas.annotations.media.Content)})
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

    @Operation(summary="비밀번호 변경", description="현재 비밀번호 확인, 새 비밀번호와 확인 값 일치가 필요합니다. 새 비밀번호는 영문+숫자 8~72자·UTF-8 72바이트 이내이며 기존 값과 달라야 합니다. 현재 비밀번호 불일치는 400입니다.", parameters=@Parameter(name="X-CSRF-TOKEN", in=ParameterIn.HEADER, required=true, schema=@io.swagger.v3.oas.annotations.media.Schema(type="string"), description="같은 브라우저에서 GET /auth/csrf로 받은 token. 가입·로그인 후 다시 발급하세요."))
    @ApiResponses({@ApiResponse(responseCode="200", description="변경 완료"),@ApiResponse(responseCode="401", description="로그인 필요", content=@io.swagger.v3.oas.annotations.media.Content)})
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
