package com.trueket.backend.user.repository;
import com.trueket.backend.user.entity.PhoneVerification;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PhoneVerification> findByPhoneHashAndPurpose(String phoneHash, String purpose);
}