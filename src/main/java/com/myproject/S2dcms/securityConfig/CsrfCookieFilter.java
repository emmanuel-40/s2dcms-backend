package com.myproject.S2dcms.securityConfig;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.lang.NonNull;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Forces the deferred CSRF token to load so CookieCsrfTokenRepository
 * writes the non-HttpOnly XSRF-TOKEN cookie the SPA can read.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken == null) {
            csrfToken = (CsrfToken) request.getAttribute("_csrf");
        }

        if (csrfToken != null) {
            // Touch the token so the cookie is written on this response
            String token = csrfToken.getToken();
            if (token != null) {
                response.setHeader("X-XSRF-TOKEN", token);
            }
        }

        filterChain.doFilter(request, response);
    }
}
