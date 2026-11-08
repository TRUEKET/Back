package com.trueket.backend.report.repository;

import com.trueket.backend.report.entity.ReportIdentifier;
import com.trueket.backend.report.entity.ReportIdentifierId;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

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
}