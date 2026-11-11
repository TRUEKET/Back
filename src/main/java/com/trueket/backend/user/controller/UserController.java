package com.trueket.backend.user.controller;

import com.trueket.backend.user.dto.PasswordChangeRequest;
import com.trueket.backend.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/me")
public class UserController {
    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @PatchMapping("/password")
    public Map<String, String> changePassword(
            Authentication authentication,
            @Valid @RequestBody PasswordChangeRequest input
    ) {
        users.changePassword(Long.valueOf(authentication.getName()), input);
        return Map.of("message", "비밀번호를 바꿨어요");
    }
}
