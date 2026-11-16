package com.myproject.S2dcms.securityConfig;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.web.client.RestClient;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final RequestIdFilter requestIdFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          RequestIdFilter requestIdFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.requestIdFilter = requestIdFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookieCustomizer(cookie -> {
            boolean production = isProductionEnvironment();
            cookie.path("/");
            cookie.sameSite(production ? "None" : "Lax");
            cookie.secure(production);
        });

        http.csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        /*
                         * Skip CSRF only where no XSRF cookie possible.
                         */
                        .ignoringRequestMatchers(
                                "/api/auth/login",
                                "/api/auth/refresh-token",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password",
                                "/api/auth/reset-password/validate",
                                "/api/auth/logout",
                                "/api/students/auth/**",
                                "/api/department/auth/**",
                                "/api/contact/**"
                        )
                )
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        /* Public endpoints — no authentication required */
                        .requestMatchers("/api/auth/login", "/api/auth/refresh-token",
                                "/api/auth/forgot-password", "/api/auth/reset-password",
                                "/api/auth/reset-password/validate", "/api/auth/logout",
                                "/api/auth/me", "/api/auth/csrf", "/api/students/auth/**",
                                "/api/department/auth/**", "/api/contact/**").permitAll()
                        .requestMatchers("/health").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(
                                "/api/students/auth/**",
                                "/api/department/auth/**",
                                "/api/auth/**"
                        ).permitAll()
                        .requestMatchers("/api/department/all").permitAll()
                        .requestMatchers("/uploads/**").permitAll()
                        .requestMatchers("/api/contact/**").permitAll()
                        /* Role-based endpoints */
                        .requestMatchers("/api/ai/summarize").hasRole("DEPARTMENT")
                        .requestMatchers("/api/ai/suggest-reply").hasRole("DEPARTMENT")
                        .requestMatchers("/api/ai/write-complaint").hasRole("STUDENT")
                        .requestMatchers("/api/department/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/students/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/students/**").hasRole("STUDENT")
                        .requestMatchers("/api/department/**").hasRole("DEPARTMENT")
                        .requestMatchers("/api/user/change-password")
                        .hasAnyRole("STUDENT", "DEPARTMENT")
                        /* Everything else requires authentication */
                        .anyRequest().authenticated()
                )
                .headers(headers -> headers
                        .contentSecurityPolicy(csp ->
                                csp.policyDirectives("default-src 'self' ")
                        )
                        .frameOptions(frame -> frame.sameOrigin())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000)
                        )
                )
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                /*
                 * 401 for missing JWT; 403 for permission/CSRF issues.
                 */
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")
                        )
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            Authentication authentication =
                                    SecurityContextHolder.getContext().getAuthentication();
                            boolean anonymous = authentication == null
                                    || !authentication.isAuthenticated()
                                    || authentication instanceof AnonymousAuthenticationToken;

                            /* Unauthenticated callers always get 401 so the SPA can
                             * distinguish "not logged in" from "logged in but forbidden".
                             * CSRF-only violations on authenticated calls stay 403. */
                            if (anonymous) {
                                writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "Unauthorized");
                            } else if (accessDeniedException instanceof InvalidCsrfTokenException
                                    || accessDeniedException instanceof MissingCsrfTokenException) {
                                writeJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                                        "CSRF token missing or invalid");
                            } else {
                                writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden");
                            }
                        })
                )
                /*
                 * Authenticate before CSRF validation.
                 */
                .addFilterBefore(jwtAuthFilter, CsrfFilter.class)
                /*
                 * Publish CSRF token to response.
                 */
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                /* Request ID for log correlation - runs first so every downstream
                 * filter and controller sees it in MDC. */
                .addFilterBefore(requestIdFilter, JwtAuthFilter.class);

        return http.build();
    }

    private void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    private boolean isProductionEnvironment() {
        String env = System.getenv("ENVIRONMENT");
        return "production".equalsIgnoreCase(env)
                || "render".equalsIgnoreCase(env)
                || "vercel".equalsIgnoreCase(env);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of(
                "http://localhost:5173",
                "https://student-complaints-tau.vercel.app"
        ));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(List.of("X-XSRF-TOKEN", "Set-Cookie", "Content-Disposition", "X-Request-ID"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
