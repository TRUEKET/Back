
package com.trueket.backend.global.ratelimit;

import com.trueket.backend.global.exception.BusinessException;
import com.trueket.backend.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LookupRateLimiter {

    private static final int MAX_REQUESTS = 30;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final ConcurrentHashMap<String, WindowCounter> counters =
            new ConcurrentHashMap<>();

    private final Clock clock;

    public LookupRateLimiter() {
        this(Clock.systemUTC());
    }

    // 테스트에서 시간을 제어하기 위한 생성자
    LookupRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void check(String clientIp) {
        Instant now = clock.instant();

        WindowCounter counter = counters.compute(
                clientIp,
                (ip, existing) -> {
                    if (existing == null
                            || !now.isBefore(existing.expiresAt())) {
                        return new WindowCounter(
                                1,
                                now.plus(WINDOW)
                        );
                    }

                    // 30회 초과 시 카운터를 계속 증가시키지 않음
                    return new WindowCounter(
                            Math.min(
                                    existing.count() + 1,
                                    MAX_REQUESTS + 1
                            ),
                            existing.expiresAt()
                    );
                }
        );

        if (counter.count() > MAX_REQUESTS) {
            throw new BusinessException(
                    ErrorCode.TOO_MANY_REQUESTS
            );
        }
    }

    // 만료된 IP 기록 정리
    public void removeExpiredEntries() {
        Instant now = clock.instant();

        counters.entrySet().removeIf(
                entry -> !now.isBefore(
                        entry.getValue().expiresAt()
                )
        );
    }

    // 테스트용 조회
    int getTrackedIpCount() {
        return counters.size();
    }

    private record WindowCounter(
            int count,
            Instant expiresAt
    ) {
    }
}
