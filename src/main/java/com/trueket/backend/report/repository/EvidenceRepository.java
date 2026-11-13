package com.trueket.backend.report.repository;

import com.trueket.backend.report.entity.Evidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvidenceRepository extends JpaRepository<Evidence, Long> {

    // 특정 신고의 증거 목록 조회
    List<Evidence> findAllByReport_IdOrderByCreatedAtAsc(
            Long reportId
    );
}