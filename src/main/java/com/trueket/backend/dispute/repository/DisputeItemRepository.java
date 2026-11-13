package com.trueket.backend.dispute.repository;

import com.trueket.backend.dispute.entity.DisputeItem;
import com.trueket.backend.dispute.entity.enums.DisputeItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DisputeItemRepository
        extends JpaRepository<DisputeItem, Long> {

    // 특정 신고에 연결된 이의제기 처리 항목 조회
    List<DisputeItem> findAllByReport_Id(Long reportId);

    // 특정 신고의 특정 이의제기 처리 항목 조회
    Optional<DisputeItem> findByDispute_IdAndReport_Id(
            Long disputeId,
            Long reportId
    );

    // 특정 상태의 처리 항목 조회
    List<DisputeItem> findAllByStatus(DisputeItemStatus status);

    // 특정 이의제기에 연결된 모든 처리 항목 조회
    List<DisputeItem> findAllByDispute_Id(Long disputeId);

    void deleteAllByReport_Id(Long reportId);
}