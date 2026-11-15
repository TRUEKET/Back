package com.trueket.backend.dispute.repository;

import com.trueket.backend.dispute.entity.DisputeItem;
import com.trueket.backend.dispute.entity.enums.DisputeItemStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
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

    // 이의제기를 한 번이라도 받은 신고인지 확인
    boolean existsByReport_Id(Long reportId);

    @Query("""
    SELECT CASE WHEN COUNT(di) > 0 THEN true ELSE false END
    FROM DisputeItem di
    WHERE di.report.id = :reportId
      AND di.dispute.identifier.id = :identifierId
    """)
    boolean existsByReportAndIdentifier(
            @Param("reportId") Long reportId,
            @Param("identifierId") Long identifierId
    );
    
    // 특정 식별자의 이의제기 처리 중인 항목 존재 여부
    @Query("""
    SELECT CASE WHEN COUNT(di) > 0 THEN true ELSE false END
    FROM DisputeItem di
    WHERE di.dispute.identifier.id = :identifierId
      AND di.status IN :statuses
    """)
    boolean existsProcessingItem(
            @Param("identifierId") Long identifierId,
            @Param("statuses") List<DisputeItemStatus> statuses
    );

    // 3일 무응답으로 만료된 항목 조회
    List<DisputeItem> findAllByStatusAndDeadlineLessThanEqual(
            DisputeItemStatus status,
            OffsetDateTime now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    SELECT di
    FROM DisputeItem di
    JOIN FETCH di.report r
    JOIN FETCH r.user
    WHERE di.dispute.id = :disputeId
      AND di.report.id = :reportId
    """)
    Optional<DisputeItem> findForDecision(
            @Param("disputeId") Long disputeId,
            @Param("reportId") Long reportId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    SELECT di
    FROM DisputeItem di
    JOIN FETCH di.report
    WHERE di.id = :id
    """)
    Optional<DisputeItem> findByIdForUpdate(
            @Param("id") Long id
    );

    @Query("""
    SELECT di
    FROM DisputeItem di
    JOIN FETCH di.dispute
    WHERE di.report.id IN :reportIds
    """)
    List<DisputeItem> findAllForReportIds(
            @Param("reportIds") List<Long> reportIds
    );
}