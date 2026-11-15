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
        return GroupedOpenApi.builder().group("all").pathsToMatch("/**").addOpenApiCustomizer(this::documentLogout).build();
    }

    @Bean
    GroupedOpenApi trueshotOpenApi() {
        return GroupedOpenApi.builder().group("trueshot")
                .pathsToMatch("/trueshot", "/trueshot/**", "/s/**").build();
    }

    @Bean
    GroupedOpenApi accountsOpenApi() {
        return GroupedOpenApi.builder().group("accounts")
                .pathsToMatch("/auth/**", "/me", "/me/**")
                .pathsToExclude("/me/reports", "/me/reports/**")
                .addOpenApiCustomizer(this::documentLogout).build();
    }

    @Bean
    GroupedOpenApi analysisOpenApi() {
        return GroupedOpenApi.builder().group("analysis")
                .pathsToMatch("/analyze", "/analyze/**").build();
    }

    private void documentLogout(OpenAPI api) {
        var operation = new io.swagger.v3.oas.models.Operation()
                .operationId("logout").addTagsItem("인증").summary("로그아웃")
                .description("보안 필터가 처리합니다. 현재 세션을 무효화하고 해당 브라우저의 임시 판독 원문·사진을 제거합니다. 익명도 호출 가능하며 CSRF는 필수입니다. 성공 후 토큰을 새로 받으세요. JSESSIONID는 브라우저가 관리하므로 Authorize에서 직접 입력하지 않습니다.")
                .addParametersItem(new io.swagger.v3.oas.models.parameters.Parameter()
                        .name("X-CSRF-TOKEN").in("header").required(true)
                        .description("같은 브라우저에서 GET /auth/csrf로 받은 token")
                        .schema(new io.swagger.v3.oas.models.media.StringSchema()))
                .responses(new io.swagger.v3.oas.models.responses.ApiResponses()
                        .addApiResponse("204", new io.swagger.v3.oas.models.responses.ApiResponse().description("로그아웃 완료, 응답 본문 없음"))
                        .addApiResponse("403", new io.swagger.v3.oas.models.responses.ApiResponse().description("CSRF 누락·불일치")));
        api.path("/auth/logout", new io.swagger.v3.oas.models.PathItem().post(operation));
    }
}
