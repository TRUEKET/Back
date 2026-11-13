package com.trueket.backend.global.config;
import com.trueket.backend.user.service.SmsSender;
import org.springframework.context.annotation.*;
@Configuration
public class SmsConfig {
    @Bean @Profile("demo")
    SmsSender demoSmsSender() { return (phone, code) -> {}; }
}