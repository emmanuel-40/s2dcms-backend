package com.myproject.S2dcms.dto.auth;

/**
 * Body returned by login and refresh-token.
 *
 * <p>Cookie-native by design: this carries identity only. The access and refresh tokens travel
 * exclusively in the HttpOnly cookies written by {@code AuthService#setAuthCookies}, so no
 * credential can ever be read by JavaScript, cached by a proxy, or written to a log line.
 */
public class AuthResponse {

    private String email;
    private String role;

    public AuthResponse() {
    }

    public AuthResponse(String email, String role) {
        this.email = email;
        this.role = role;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
