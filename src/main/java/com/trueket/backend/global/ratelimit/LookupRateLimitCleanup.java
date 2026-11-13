
package com.trueket.backend.global.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LookupRateLimitCleanup {

    private final LookupRateLimiter lookupRateLimiter;

    @Scheduled(fixedDelay = 60000)
    public void cleanup() {
        lookupRateLimiter.removeExpiredEntries();
    }
}
