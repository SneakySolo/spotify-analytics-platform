package com.wrapitup.wrap_it_up.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import com.wrapitup.wrap_it_up.auth.OAuthSuccessHandler;
import com.wrapitup.wrap_it_up.auth.OAuthFailureHandler;

@Configuration
public class SecurityConfig {
    private final OAuthSuccessHandler successHandler;
    private final OAuthFailureHandler failureHandler;

    public SecurityConfig(
            OAuthSuccessHandler successHandler,
            OAuthFailureHandler failureHandler) {
        this.successHandler = successHandler;
        this.failureHandler = failureHandler;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/css/**", "/js/**", "/actuator/health", "/oauth2/**", "/login/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth -> oauth
                        .successHandler(successHandler)
                        .failureHandler(failureHandler))
                .logout(logout -> logout.logoutSuccessUrl("/").invalidateHttpSession(true).clearAuthentication(true));
        return http.build();
    }
}
