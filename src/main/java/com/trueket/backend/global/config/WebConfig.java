
package com.trueket.backend.global.config;

import com.trueket.backend.global.ratelimit.LookupRateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final LookupRateLimitInterceptor lookupRateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(lookupRateLimitInterceptor)
                .addPathPatterns("/lookup", "/lookup/**");
    }
}
