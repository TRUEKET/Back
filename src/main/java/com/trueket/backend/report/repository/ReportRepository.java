package com.trueket.backend.report.repository;

import com.trueket.backend.report.entity.Report;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {

    // 로그인한 사용자의 신고 목록 조회
    Page<Report> findAllByUser_IdOrderByCreatedAtDesc(
            Long userId,
            Pageable pageable
    );

    // 로그인한 사용자의 특정 신고 상세 조회
    Optional<Report> findByIdAndUser_Id(
            Long reportId,
            Long userId
    );

    // 동일한 신고자가 동일한 식별 정보에 이미 신고했는지 확인
    @Query("""
    SELECT CASE WHEN COUNT(ri) > 0 THEN true ELSE false END
    FROM Report r
    JOIN r.reportIdentifiers ri
    WHERE r.reporterHash = :reporterHash
      AND ri.identifier.id = :identifierId
    """)
    boolean existsDuplicateReport(
            @Param("reporterHash") String reporterHash,
            @Param("identifierId") Long identifierId
    );
}