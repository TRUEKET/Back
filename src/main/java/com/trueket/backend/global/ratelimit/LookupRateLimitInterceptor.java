
package com.trueket.backend.global.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class LookupRateLimitInterceptor implements HandlerInterceptor {

    private final LookupRateLimiter lookupRateLimiter;

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        String clientIp = request.getRemoteAddr();

        lookupRateLimiter.check(clientIp);

        return true;
    }
}
