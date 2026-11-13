package com.trueket.backend.user.service;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.global.storage.SupabaseStorageDeletion;
import com.trueket.backend.user.repository.UserRepository;
import com.trueket.backend.user.repository.WithdrawalRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WithdrawalService {
    private final UserRepository users;
    private final WithdrawalRepository owned;
    private final SupabaseStorageDeletion storage;
    public WithdrawalService(UserRepository users, WithdrawalRepository owned, SupabaseStorageDeletion storage) {
        this.users=users; this.owned=owned; this.storage=storage;
    }

    @Transactional(timeout = 30)
    public void withdraw(Long userId) {
        var user = users.findForUpdateById(userId).filter(value -> "ACTIVE".equals(value.getStatus()))
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,"로그인이 필요합니다."));
        try {
            var targets = owned.lockAndCollect(userId);
            storage.deleteAll(targets);
            owned.deleteOwnedRows(userId,user.getPhoneHash());
            users.delete(user);
            users.flush();
        } catch (DataAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"탈퇴 처리를 완료하지 못했어요. 잠시 후 다시 시도해 주세요");
        }
    }
}
