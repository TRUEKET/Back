package com.trueket.backend.user.repository;

import com.trueket.backend.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.id = :id")
    Optional<User> findForUpdateById(Long id);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByIdAndStatus(Long id, String status);

    boolean existsByPhoneHash(String phoneHash);

    boolean existsByPhoneHashAndIdNot(String phoneHash, Long id);
}