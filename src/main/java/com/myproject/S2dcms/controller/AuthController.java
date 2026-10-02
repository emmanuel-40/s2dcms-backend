package com.myproject.S2dcms.controller;

import com.myproject.S2dcms.Service.AuthService;
import com.myproject.S2dcms.dto.auth.AuthResponse;
import com.myproject.S2dcms.dto.auth.ChangePasswordRequest;
import com.myproject.S2dcms.dto.auth.LoginRequest;
import com.myproject.S2dcms.dto.verification.ForgotPasswordRequest;
import com.myproject.S2dcms.dto.verification.ResetPasswordRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<AuthResponse> login(
            @RequestBody LoginRequest request,
            jakarta.servlet.http.HttpServletResponse response
    ) {
        return ResponseEntity.ok(authService.login(request, response));
    }

    /**
     * Rotates the cookie pair. No request body is read - the presented credential is the
     * HttpOnly {@code refreshToken} cookie.
     */
    @PostMapping("/auth/refresh-token")
    public ResponseEntity<AuthResponse> refreshToken(
            jakarta.servlet.http.HttpServletResponse response,
            jakarta.servlet.http.HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(authService.refreshToken(response, httpRequest));
    }

    @PostMapping("/auth/forgot-password")
    public ResponseEntity<Void> forgotPassword(
            @RequestBody ForgotPasswordRequest request
    ) {
        authService.forgotPassword(request);
        return ResponseEntity.noContent().build();
    }


    @PostMapping("/auth/reset-password")
    public ResponseEntity<Void> resetPassword(
            @RequestBody ResetPasswordRequest request
    ) {
        authService.resetPassword(request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Reports whether a reset link is still usable, so the SPA can say "this link has already been
     * used" the moment the page opens.
     *
     */
    @GetMapping("/auth/reset-password/validate")
    public ResponseEntity<Map<String, String>> validateResetToken(@RequestParam String token) {

        Map<String, String> body = new HashMap<>();
        String status = authService.checkResetTokenStatus(token);

        body.put("status", status);
        if ("valid".equals(status)) {
            return ResponseEntity.ok(body);
        }
        // 410 Gone: the link existed but can no longer be used. Distinct from 404 so the SPA can
        // explain the difference instead of rendering a generic failure.
        return ResponseEntity.status(HttpStatus.GONE).body(body);
    }

    @PostMapping("/user/change-password")
    public ResponseEntity<Void> changePassword(
            @RequestBody ChangePasswordRequest request
    ) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        authService.changePassword(email, request);
        return ResponseEntity.ok().build();
    }


    /**
     * Revokes the refresh token found in the cookie and expires both auth cookies.
     * No request body is read.
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(
            jakarta.servlet.http.HttpServletResponse response,
            jakarta.servlet.http.HttpServletRequest httpRequest
    ) {
        authService.logout(response, httpRequest);
        return ResponseEntity.noContent().build();
    }

    /**
     * Hands the SPA its double-submit CSRF token in a body it can read cross-origin.
     *
     * <p>The {@code XSRF-TOKEN} cookie is only visible same-origin ({@code document.cookie}
     * cannot see another site's cookies), so the value is also served here as JSON and as the
     * CORS-exposed {@code X-XSRF-TOKEN} response header. The SPA calls this to bootstrap a
     * token before its first state-changing request, and again after a 403 CSRF rejection.
     */
    @GetMapping("/auth/csrf")
    public ResponseEntity<Map<String, String>> csrf(jakarta.servlet.http.HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            token = (CsrfToken) request.getAttribute("_csrf");
        }
        if (token == null || token.getToken() == null) {
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.ok(Map.of("token", token.getToken()));
    }

    @GetMapping("/auth/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Map<String, Object> user = Map.of(
            "email", email,
            "role", SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .findFirst()
                .map(auth -> auth.getAuthority().replace("ROLE_", ""))
                .orElse("USER")
        );
        return ResponseEntity.ok(user);
    }
}