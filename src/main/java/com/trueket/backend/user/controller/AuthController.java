package com.trueket.backend.user.controller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name="인증")
@ApiResponses({
    @ApiResponse(responseCode="400", description="입력 형식·검증 조건을 확인해 주세요.", content=@io.swagger.v3.oas.annotations.media.Content),
    @ApiResponse(responseCode="403", description="CSRF 토큰 누락 또는 현재 세션과 불일치", content=@io.swagger.v3.oas.annotations.media.Content),
    @ApiResponse(responseCode="500", description="서버 내부 오류. 응답과 서버 로그를 확인하세요.", content=@io.swagger.v3.oas.annotations.media.Content)
})
@RestController
public class AuthController {
    private final AuthService auth;
    private final UserRepository users;
    private final com.trueket.backend.analyze.service.AnalysisSessionStore analyses;
    public AuthController(AuthService auth, UserRepository users,
                          com.trueket.backend.analyze.service.AnalysisSessionStore analyses) {
        this.auth=auth; this.users=users; this.analyses=analyses;
    }
    @Operation(summary="CSRF 토큰 발급", description="익명·로그인 모두 사용합니다. 먼저 호출해 세션 쿠키를 유지하고 token을 X-CSRF-TOKEN 헤더에 넣으세요. 가입·로그인·로그아웃 후 다시 호출하세요. Swagger에서는 JSESSIONID를 직접 입력하지 않고 같은 화면에서 요청하세요.")
    @ApiResponses({@ApiResponse(responseCode="200", description="headerName·parameterName·token 반환")})
    @GetMapping("/auth/csrf") public CsrfToken csrf(@Parameter(hidden=true) CsrfToken token) { return token; }
    @Operation(summary="가입용 휴대폰 인증번호 발급", description="010 번호를 문자열로 입력합니다. demo에서는 실제 SMS 대신 demoSms.code를 반환합니다. 유효 3분, 재발급 60초 제한. 같은 세션으로 인증을 이어가세요.", parameters=@Parameter(name="X-CSRF-TOKEN", in=ParameterIn.HEADER, required=true, schema=@io.swagger.v3.oas.annotations.media.Schema(type="string"), description="같은 브라우저에서 GET /auth/csrf로 받은 token. 가입·로그인 후 다시 발급하세요."))
    @ApiResponses({@ApiResponse(responseCode="200", description="verificationId·expiresAt 및 demo 모드 수신함"),@ApiResponse(responseCode="429", description="재발송 대기시간 미충족", content=@io.swagger.v3.oas.annotations.media.Content)})
    @PostMapping("/auth/phone/send")
    public PhoneSent send(@Valid @RequestBody PhoneSend input, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        PhoneSent sent = auth.send(input);
        request.getSession().setAttribute("phoneVerificationId", sent.verificationId());
        request.getSession().removeAttribute("verifiedPhoneId");
        return sent;
    }

    @Operation(summary="가입용 휴대폰 인증번호 확인", description="발급 응답의 verificationId·번호·6자리 코드를 전송합니다. 오입력 5회·만료·타 세션은 거부합니다. 성공한 세션에서 가입하세요.", parameters=@Parameter(name="X-CSRF-TOKEN", in=ParameterIn.HEADER, required=true, schema=@io.swagger.v3.oas.annotations.media.Schema(type="string"), description="같은 브라우저에서 GET /auth/csrf로 받은 token. 가입·로그인 후 다시 발급하세요."))
    @ApiResponses({@ApiResponse(responseCode="200", description="인증 성공, 응답 본문 없음")})
    @PostMapping("/auth/phone/verify")
    public void verify(@Valid @RequestBody PhoneVerify input, HttpServletRequest request) {
        requireVerificationSession(request, "phoneVerificationId", input.verificationId());
        auth.verify(input);
        request.getSession().setAttribute("verifiedPhoneId", input.verificationId());
    }
    @Operation(summary="회원가입 및 자동 로그인", description="같은 세션에서 휴대폰 인증 후 호출합니다. 개인정보 동의 필수, 비밀번호는 영문+숫자 8~72자. 성공 시 세션 ID가 바뀌며 자동 로그인됩니다. 이후 CSRF 토큰을 새로 받으세요.", parameters=@Parameter(name="X-CSRF-TOKEN", in=ParameterIn.HEADER, required=true, schema=@io.swagger.v3.oas.annotations.media.Schema(type="string"), description="같은 브라우저에서 GET /auth/csrf로 받은 token. 가입·로그인 후 다시 발급하세요."))
    @ApiResponses({@ApiResponse(responseCode="200", description="가입된 사용자 정보"),@ApiResponse(responseCode="409", description="이메일·전화번호 중복", content=@io.swagger.v3.oas.annotations.media.Content)})
    @PostMapping("/auth/signup")
    public UserResponse signup(@Valid @RequestBody Signup input, HttpServletRequest request, HttpServletResponse response) {
        requireVerificationSession(request, "verifiedPhoneId", input.verificationId());
        User user = auth.signup(input);
        request.getSession().removeAttribute("phoneVerificationId");
        request.getSession().removeAttribute("verifiedPhoneId");
        return authenticate(user, request, response);
    }
    @Operation(summary="이메일·비밀번호 로그인", description="서버 세션 방식입니다. 성공 후 브라우저 쿠키를 유지하며 CSRF를 새로 받으세요. 익명 판독은 같은 세션에서 유지하고 다른 계정으로 전환하면 이전 판독은 삭제합니다.", parameters=@Parameter(name="X-CSRF-TOKEN", in=ParameterIn.HEADER, required=true, schema=@io.swagger.v3.oas.annotations.media.Schema(type="string"), description="같은 브라우저에서 GET /auth/csrf로 받은 token. 가입·로그인 후 다시 발급하세요."))
    @ApiResponses({@ApiResponse(responseCode="200", description="로그인 사용자 정보"),@ApiResponse(responseCode="401", description="이메일·비밀번호 불일치 또는 비활성 계정", content=@io.swagger.v3.oas.annotations.media.Content)})
    @PostMapping("/auth/login")
    public UserResponse login(@Valid @RequestBody Login input, HttpServletRequest request, HttpServletResponse response) {
        return authenticate(auth.login(input), request, response);
    }
    @Operation(summary="로그인 사용자 조회", description="로그인 세션의 사용자 ID·이메일·휴대폰 뒤 4자리만 반환합니다.")
    @ApiResponses({@ApiResponse(responseCode="200", description="사용자 요약"),@ApiResponse(responseCode="401", description="로그인 필요", content=@io.swagger.v3.oas.annotations.media.Content)})
    @SecurityRequirement(name="sessionAuth")
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
        var previous = SecurityContextHolder.getContext().getAuthentication();
        var session = request.getSession();
        if (previous != null && previous.isAuthenticated()
                && !(previous instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
                && !previous.getName().equals(user.getId().toString())) {
            Object owner = session.getAttribute("analysisOwner");
            if (owner instanceof java.util.UUID id) analyses.deleteByOwner(id);
            session.removeAttribute("analysisOwner");
        }
        request.changeSessionId();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.getId().toString(), null, List.of()));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        new HttpSessionCsrfTokenRepository().saveToken(null, request, response);
        return UserResponse.from(user);
    }
}
