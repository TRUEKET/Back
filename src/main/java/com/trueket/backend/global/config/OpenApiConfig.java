package com.trueket.backend.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info().title("TRUEKET API").version("v1")
                        .description("TRUEKET 서비스 전체 API 문서입니다. "
                                + "API별 인증 조건과 요청·응답 정보는 각 항목을 확인하세요."))
                .components(new Components()
                        .addSecuritySchemes("sessionAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE).name("JSESSIONID")));
    }

    @Bean
    GroupedOpenApi allOpenApi() {
        return GroupedOpenApi.builder().group("all").pathsToMatch("/**").build();
    }

    @Bean
    GroupedOpenApi trueshotOpenApi() {
        return GroupedOpenApi.builder().group("trueshot")
                .pathsToMatch("/trueshot", "/trueshot/**", "/s/**").build();
    }
}
