package com.trueket.backend.dispute.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeRecoveryService {

    private final EntityManager entityManager;

    // 1분마다 오래된 CHECKING 접수 정리
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    @Transactional
    public void recoverStaleChecking() {

        int count = entityManager.createNativeQuery("""
                UPDATE public.disputes
                SET status = 'FAILED',
                    resolved_at = NOW(),
                    check_result =
                        '{"error":"PROCESSING_TIMEOUT"}'::jsonb
                WHERE status = 'CHECKING'
                  AND created_at < NOW() - INTERVAL '5 minutes'
                """)
                .executeUpdate();

        if (count > 0) {
            log.warn(
                    "RP-08 오래된 CHECKING 접수 종료: {}건",
                    count
            );
        }
    }
}