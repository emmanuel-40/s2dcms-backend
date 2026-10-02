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

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
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
                        // Only skip CSRF where the SPA cannot yet have an XSRF cookie.
                        // Do NOT ignore /api/auth/me — that GET seeds the XSRF-TOKEN cookie.
                        .ignoringRequestMatchers(
                                "/api/auth/login",
                                "/api/auth/refresh-token",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password",
                                // GET-only token probe, safe to call before a session exists.
                                "/api/auth/reset-password/validate",
                                "/api/auth/logout",
                                "/api/students/auth/**",
                                "/api/department/auth/**",
                                "/api/contact/**"
                        )
                )
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
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
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health").permitAll()

                        // The ERROR dispatcher re-enters the filter chain. Without this, any
                        // exception thrown while serving a request is forwarded to /error,
                        // which then hits "anyRequest().authenticated()" while anonymous and
                        // comes back as 401 - masking the real error (e.g. a missing image
                        // served from /uploads/**) behind an "Unauthorized".
                        .requestMatchers("/error").permitAll()

                        // /auth/me MUST come before the broad /api/auth/** rule
                        .requestMatchers("/api/auth/me").authenticated()

                        .requestMatchers(
                                "/api/students/auth/**",
                                "/api/department/auth/**",
                                "/api/auth/**"
                        ).permitAll()

                        .requestMatchers("/api/department/all").permitAll()
                        .requestMatchers("/uploads/**").permitAll()
                        .requestMatchers("/api/contact/**").permitAll()

                        .requestMatchers("/api/ai/summarize").hasRole("DEPARTMENT")
                        .requestMatchers("/api/ai/suggest-reply").hasRole("DEPARTMENT")
                        .requestMatchers("/api/ai/write-complaint").hasRole("STUDENT")

                        .requestMatchers("/api/department/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/students/admin/**").hasRole("ADMIN")

                        .requestMatchers("/api/students/**").hasRole("STUDENT")
                        .requestMatchers("/api/department/**").hasRole("DEPARTMENT")

                        .requestMatchers("/api/user/change-password")
                        .hasAnyRole("STUDENT", "DEPARTMENT")

                        .anyRequest().authenticated()
                )
                // Missing/expired JWT must be 401 so the SPA can refresh.
                // Real permission failures stay 403. CSRF failures stay 403 too, with a
                // distinct message the SPA uses to re-seed the token and retry once.
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

                            if (anonymous) {
                                // No session: 401 first so the SPA attempts a refresh.
                                writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
                            } else if (accessDeniedException instanceof InvalidCsrfTokenException
                                    || accessDeniedException instanceof MissingCsrfTokenException) {
                                // Signed-in caller with a missing/stale X-XSRF-TOKEN header.
                                // Actionable: the SPA re-seeds via GET /api/auth/csrf and retries.
                                writeJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                                        "CSRF token missing or invalid");
                            } else {
                                writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden");
                            }
                        })
                )
                // Authenticate BEFORE CSRF validation: otherwise a CSRF failure lands while the
                // SecurityContext is still anonymous and is reported as 401, which the SPA
                // misreads as an expired session and can never recover from.
                .addFilterBefore(jwtAuthFilter, CsrfFilter.class)
                // Must run AFTER CsrfFilter has published the deferred token attribute.
                // It hands the SPA the CORS-exposed X-XSRF-TOKEN response header + cookie.
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);

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
        configuration.setExposedHeaders(List.of("X-XSRF-TOKEN", "Set-Cookie", "Content-Disposition"));

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
