package com.trueket.backend.dispute.repository;

import com.trueket.backend.dispute.entity.Dispute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    // 특정 식별 정보의 이의제기 조회
    Optional<Dispute> findByIdentifier_Id(Long identifierId);

    // 특정 식별 정보에 이의제기가 존재하는지 확인
    boolean existsByIdentifier_Id(Long identifierId);

    // 접수번호로 이의제기 조회
    Optional<Dispute> findByReceiptNo(String receiptNo);
}