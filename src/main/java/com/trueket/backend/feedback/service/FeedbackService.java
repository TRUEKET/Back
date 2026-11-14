package com.trueket.backend.feedback.service;

import com.trueket.backend.analyze.service.AnalysisSessionStore;
import com.trueket.backend.feedback.entity.Feedback;
import com.trueket.backend.feedback.entity.FeedbackKind;
import com.trueket.backend.feedback.repository.FeedbackRepository;
import com.trueket.backend.global.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Service
public class FeedbackService {
    private final AnalysisSessionStore sessions;
    private final FeedbackRepository feedback;
    private final TransactionTemplate transactions;

    public FeedbackService(AnalysisSessionStore sessions, FeedbackRepository feedback,
                           PlatformTransactionManager manager) {
        this.sessions = sessions;
        this.feedback = feedback;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void submit(UUID analyzeId, UUID owner, FeedbackKind kind) {
        // Valid analysis sessions live on this process only. Hold their lock through DB commit,
        // so parallel submissions and session deletion cannot race the duplicate check.
        sessions.withActiveSession(analyzeId, owner, () -> transactions.execute(status -> {
            if (feedback.existsByAnalyzeId(analyzeId)) {
                throw new ApiException(HttpStatus.CONFLICT, "이 판독에는 이미 의견을 남겼어요");
            }
            feedback.saveAndFlush(Feedback.create(analyzeId, kind));
            return null;
        }));
    }
}
