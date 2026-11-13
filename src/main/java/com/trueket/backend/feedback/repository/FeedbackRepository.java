package com.trueket.backend.feedback.repository;

import com.trueket.backend.feedback.entity.Feedback;
import com.trueket.backend.feedback.entity.FeedbackKind;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
    boolean existsByAnalyzeId(UUID analyzeId);
    long countByAnalyzeId(UUID analyzeId);
    Page<Feedback> findByKindOrderByCreatedAtDescIdDesc(FeedbackKind kind, Pageable pageable);
}
