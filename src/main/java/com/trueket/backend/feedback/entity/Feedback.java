package com.trueket.backend.feedback.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "feedback")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Feedback {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "analyze_id", nullable = false)
    private UUID analyzeId;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private FeedbackKind kind;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public static Feedback create(UUID analyzeId, FeedbackKind kind) {
        Feedback feedback = new Feedback();
        feedback.analyzeId = analyzeId;
        feedback.kind = kind;
        feedback.createdAt = OffsetDateTime.now();
        return feedback;
    }
}
