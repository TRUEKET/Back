
package com.trueket.backend.user.config;

import com.trueket.backend.user.service.SmsSender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("test")
public class TestSmsSenderConfig {

    @Bean
    public SmsSender smsSender() {
        return (phone, code) -> {
            // 실제 SMS 발송 없이 테스트 진행
        };
    }
}
