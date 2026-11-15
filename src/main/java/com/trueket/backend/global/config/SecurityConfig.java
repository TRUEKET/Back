package com.trueket.backend.global.config;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, com.trueket.backend.user.repository.UserRepository users,
            com.trueket.backend.analyze.service.AnalysisLogoutHandler analysisLogout) throws Exception {
        http.addFilterBefore(new ActiveUserFilter(users), org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        http.authorizeHttpRequests(auth -> auth
            .requestMatchers("/auth/**", "/analyze", "/analyze/**", "/lookup", "/lookup/**", "/s/**", "/error").permitAll()
            .anyRequest().authenticated());
        http.exceptionHandling(errors -> errors
            .authenticationEntryPoint((req, res, e) -> res.sendError(401))
            .accessDeniedHandler((req, res, e) -> res.sendError(403)));
        http.logout(logout -> logout.logoutUrl("/auth/logout").addLogoutHandler(analysisLogout).logoutSuccessHandler((req, res, auth) -> res.setStatus(204)));
        return http.build();
    }
}
