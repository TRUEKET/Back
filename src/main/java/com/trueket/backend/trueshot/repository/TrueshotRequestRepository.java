package com.trueket.backend.trueshot.repository;

import com.trueket.backend.trueshot.entity.TrueshotRequest;
import com.trueket.backend.trueshot.entity.enums.TrueshotPhase;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TrueshotRequestRepository extends JpaRepository<TrueshotRequest, Long> {
    List<TrueshotRequest> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserId(Long userId);

    Optional<TrueshotRequest> findByIdAndUserId(Long id, Long userId);

    Optional<TrueshotRequest> findByToken(String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from TrueshotRequest request where request.token = :token")
    Optional<TrueshotRequest> findByTokenForUpdate(String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from TrueshotRequest request where request.id = :id")
    Optional<TrueshotRequest> findByIdForUpdate(Long id);

    List<TrueshotRequest> findByExpiresAtLessThanEqualAndPhaseIn(
            OffsetDateTime expiresAt, Collection<TrueshotPhase> phases);
}
