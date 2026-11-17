package com.trueket.backend.global.config;

import com.trueket.backend.analyze.service.AnalysisLogoutHandler;
import com.trueket.backend.user.repository.UserRepository;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            UserRepository users,
            AnalysisLogoutHandler analysisLogout
    ) throws Exception {

        // 기존 사용자 상태 확인 필터 유지
        http.addFilterBefore(
                new ActiveUserFilter(users),
                AuthorizationFilter.class
        );

        http.cors(Customizer.withDefaults());

        // 공개 이의제기 접수 API만 CSRF 검사 제외
        http.csrf(csrf -> csrf
                .ignoringRequestMatchers(
                        request ->
                                "POST".equals(request.getMethod())
                                        && "/disputes".equals(
                                                request.getServletPath()
                                        )
                )
        );

        // 접근 권한 설정
        http.authorizeHttpRequests(auth -> auth

                // 공개 API
                .requestMatchers(
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/auth/**",
                        "/analyze",
                        "/analyze/**",
                        "/lookup",
                        "/lookup/**",
                        "/s/**",
                        "/error"
                ).permitAll()

                // 이의제기 가능 여부 조회
                .requestMatchers(
                        HttpMethod.GET,
                        "/disputes/availability",
                        "/actuator/health",
                        "/actuator/health/**"
                ).permitAll()

                // 판매자 이의제기 접수 - 로그인 불필요
                .requestMatchers(
                        HttpMethod.POST,
                        "/disputes"
                ).permitAll()

                // 그 외 API는 로그인 필요
                .anyRequest().authenticated()
        );

        // 기존 예외 처리 유지
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint(
                        (req, res, e) -> res.sendError(401)
                )
                .accessDeniedHandler(
                        (req, res, e) -> res.sendError(403)
                )
        );

        // 기존 로그아웃 설정 유지
        http.logout(logout -> logout
                .logoutUrl("/auth/logout")
                .addLogoutHandler(analysisLogout)
                .logoutSuccessHandler(
                        (req, res, auth) -> res.setStatus(204)
                )
        );

        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOrigins(List.of(
                "https://trueket.vercel.app/"
        ));

        config.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));

        config.setAllowedHeaders(List.of(
                "Content-Type",
                "X-XSRF-TOKEN",
                "X-CSRF-TOKEN"
        ));

        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
