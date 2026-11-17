package com.trueket.backend.identifier.repository;

import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface IdentifierRepository
        extends JpaRepository<Identifier, Long> {

    // 유형과 해시값으로 기존 식별 정보 조회
    Optional<Identifier> findByTypeAndValueHash(
            IdentifierType type,
            String valueHash
    );

    // 동일한 식별 정보 존재 여부 확인
    boolean existsByTypeAndValueHash(
            IdentifierType type,
            String valueHash
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Identifier i WHERE i.id = :id")
    Optional<Identifier> findByIdForUpdate(
            @Param("id") Long id
    );
}