package com.trueket.backend.dispute.repository;

import com.trueket.backend.dispute.entity.Dispute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    List<Dispute> findAllByIdentifier_IdOrderByCreatedAtDesc(
            Long identifierId
    );
    // 접수번호로 이의제기 조회
    Optional<Dispute> findByReceiptNo(String receiptNo);
}