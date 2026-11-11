package com.trueket.backend.user.service;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.user.dto.PasswordChangeRequest;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder passwords;

    public UserService(UserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Transactional
    public void changePassword(Long id, PasswordChangeRequest input) {
        User user = users.findForUpdateById(id)
                .filter(value -> "ACTIVE".equals(value.getStatus()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));

        if (!passwords.matches(input.currentPassword(), user.getPasswordHash())) {
            throw invalid("현재 비밀번호가 맞지 않아요");
        }
        if (!input.newPassword().equals(input.confirmPassword())) {
            throw invalid("새 비밀번호가 서로 달라요");
        }
        if (passwords.matches(input.newPassword(), user.getPasswordHash())) {
            throw invalid("지금 쓰는 비밀번호와 달라야 해요");
        }
        if (input.newPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalid("비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
        user.changePassword(passwords.encode(input.newPassword()));
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
