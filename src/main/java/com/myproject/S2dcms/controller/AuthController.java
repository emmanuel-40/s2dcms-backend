package com.myproject.S2dcms.controller;

import com.myproject.S2dcms.Service.AuthService;
import com.myproject.S2dcms.dto.auth.AuthResponse;
import com.myproject.S2dcms.dto.auth.ChangePasswordRequest;
import com.myproject.S2dcms.dto.auth.LoginRequest;
import com.myproject.S2dcms.dto.verification.ForgotPasswordRequest;
import com.myproject.S2dcms.dto.verification.ResetPasswordRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

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