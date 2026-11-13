
package com.trueket.backend.global.ratelimit;

import com.trueket.backend.global.exception.GlobalExceptionHandler;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.lookup.controller.LookupController;
import com.trueket.backend.lookup.dto.LookupResponse;
import com.trueket.backend.lookup.service.LookupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LookupRateLimitInterceptorTest {

    private LookupService lookupService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        lookupService = mock(LookupService.class);

        LookupRateLimiter rateLimiter =
                new LookupRateLimiter();

        LookupRateLimitInterceptor interceptor =
                new LookupRateLimitInterceptor(rateLimiter);

        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new LookupController(lookupService)
                )
                .setControllerAdvice(
                        new GlobalExceptionHandler()
                )
                .addInterceptors(interceptor)
                .build();

        when(lookupService.lookup(anyString()))
                .thenReturn(new LookupResponse(
                        null,
                        IdentifierType.PHONE,
                        null,
                        "SAFE",
                        0,
                        0
                ));
    }

    @Test
    void 조회_30회까지_HTTP_200() throws Exception {
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(get("/lookup")
                            .param("q", "01012345678")
                            .with(request ->
                                    {
                                        request.setRemoteAddr("192.0.2.1");
                                        return request;
                                    }
                            ))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void 조회_31번째_요청은_HTTP_429() throws Exception {
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(get("/lookup")
                            .param("q", "01012345678")
                            .with(request -> {
                                request.setRemoteAddr("192.0.2.1");
                                return request;
                            }))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/lookup")
                        .param("q", "01012345678")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.1");
                            return request;
                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code")
                        .value("TOO_MANY_REQUESTS"));

        verify(lookupService, times(30))
                .lookup("01012345678");
    }
}
