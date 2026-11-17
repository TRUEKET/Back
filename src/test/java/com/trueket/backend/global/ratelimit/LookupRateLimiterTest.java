
package com.trueket.backend.global.ratelimit;

import com.trueket.backend.global.exception.BusinessException;
import com.trueket.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class LookupRateLimiterTest {

    private MutableClock clock;
    private LookupRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(
                Instant.parse("2026-11-11T00:00:00Z")
        );

        rateLimiter = new LookupRateLimiter(clock);
    }

    @Test
    void 동일_IP는_30회까지_허용된다() {
        for (int i = 0; i < 30; i++) {
            rateLimiter.check("192.0.2.1");
        }

        assertThat(rateLimiter.getTrackedIpCount())
                .isEqualTo(1);
    }

    @Test
    void 동일_IP의_31번째_요청은_차단된다() {
        for (int i = 0; i < 30; i++) {
            rateLimiter.check("192.0.2.1");
        }

        assertThatThrownBy(
                () -> rateLimiter.check("192.0.2.1")
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException =
                            (BusinessException) exception;

                    assertThat(businessException.getErrorCode())
                            .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
                });
    }

    @Test
    void 서로_다른_IP는_독립적으로_제한된다() {
        for (int i = 0; i < 30; i++) {
            rateLimiter.check("192.0.2.1");
        }

        assertThatCode(
                () -> rateLimiter.check("192.0.2.2")
        ).doesNotThrowAnyException();
    }

    @Test
    void 일분이_지나면_요청횟수가_초기화된다() {
        String ip = "192.0.2.1";

        for (int i = 0; i < 30; i++) {
            rateLimiter.check(ip);
        }

        assertThatThrownBy(() -> rateLimiter.check(ip))
                .isInstanceOf(BusinessException.class);

        clock.advanceSeconds(60);

        assertThatCode(() -> rateLimiter.check(ip))
                .doesNotThrowAnyException();
    }

    @Test
    void 서로_다른_IP의_만료기록을_정리한다() {
        rateLimiter.check("192.0.2.1");
        rateLimiter.check("192.0.2.2");

        assertThat(rateLimiter.getTrackedIpCount())
                .isEqualTo(2);

        clock.advanceSeconds(60);

        rateLimiter.removeExpiredEntries();

        assertThat(rateLimiter.getTrackedIpCount())
                .isZero();
    }

    @Test
    void 만료되지_않은_IP는_삭제하지_않는다() {
        rateLimiter.check("192.0.2.1");

        clock.advanceSeconds(30);

        rateLimiter.removeExpiredEntries();

        assertThat(rateLimiter.getTrackedIpCount())
                .isEqualTo(1);
    }

    @Test
    void 동시에_100회_요청하면_30회만_허용된다()
            throws Exception {

        int requestCount = 100;

        ExecutorService executor =
                Executors.newFixedThreadPool(10);

        CountDownLatch start = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger blockedCount = new AtomicInteger();

        try {
            CompletableFuture<?>[] futures =
                    new CompletableFuture[requestCount];

            for (int i = 0; i < requestCount; i++) {
                futures[i] = CompletableFuture.runAsync(() -> {
                    try {
                        start.await();

                        rateLimiter.check("192.0.2.1");
                        successCount.incrementAndGet();

                    } catch (BusinessException e) {
                        blockedCount.incrementAndGet();

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }, executor);
            }

            start.countDown();

            CompletableFuture.allOf(futures)
                    .get(10, TimeUnit.SECONDS);

            assertThat(successCount.get())
                    .isEqualTo(30);

            assertThat(blockedCount.get())
                    .isEqualTo(70);

        } finally {
            executor.shutdownNow();
        }
    }

    // 테스트에서만 사용하는 시계
    private static class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
