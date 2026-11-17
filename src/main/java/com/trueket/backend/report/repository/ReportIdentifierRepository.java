package com.trueket.backend.report.repository;

import com.trueket.backend.report.entity.ReportIdentifier;
import com.trueket.backend.report.entity.ReportIdentifierId;
import com.trueket.backend.report.entity.enums.ReportStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReportIdentifierRepository
        extends JpaRepository<ReportIdentifier, ReportIdentifierId> {

    // 특정 신고에 연결된 식별 정보 조회
    @EntityGraph(attributePaths = {"identifier"})
    List<ReportIdentifier> findAllByReport_Id(Long reportId);

    // 여러 신고에 연결된 식별 정보 일괄 조회
    @EntityGraph(attributePaths = {"identifier"})
    List<ReportIdentifier> findAllByReport_IdIn(List<Long> reportIds);

    // 특정 신고에 연결된 특정 식별 정보 조회
    List<ReportIdentifier> findAllByReport_IdAndIdentifier_Id(
            Long reportId,
            Long identifierId
    );

    void deleteAllByReport_Id(Long reportId);

    @Query("""
    SELECT ri
    FROM ReportIdentifier ri
    JOIN FETCH ri.report r
    WHERE ri.identifier.id = :identifierId
      AND ri.hidden = false
      AND r.status IN :statuses
      AND r.createdAt >= :since
""")
    List<ReportIdentifier> findActiveReportsByIdentifier(
            @Param("identifierId") Long identifierId,
            @Param("statuses") Collection<ReportStatus> statuses,
            @Param("since") OffsetDateTime since
    );

    @Query("""
    SELECT ri
    FROM ReportIdentifier ri
    JOIN FETCH ri.identifier
    WHERE ri.report.id IN :reportIds
      AND ri.hidden = false
    """)
    List<ReportIdentifier> findVisibleIdentifiersByReportIds(
            @Param("reportIds") Collection<Long> reportIds
    );
}