
package com.trueket.backend.report.repository;

import com.trueket.backend.report.entity.PendingReportEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface PendingReportEvidenceRepository
        extends JpaRepository<PendingReportEvidence, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p
            FROM PendingReportEvidence p
            WHERE p.token = :token
            """)
    Optional<PendingReportEvidence> findByTokenForUpdate(
            @Param("token") UUID token
    );
}
