package com.trueket.backend.identifier.repository;

import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import org.springframework.data.jpa.repository.JpaRepository;

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
}