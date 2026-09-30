package com.myproject.S2dcms.Service;

import com.myproject.S2dcms.Exception.*;
import com.myproject.S2dcms.dto.auth.AuthResponse;
import com.myproject.S2dcms.dto.auth.ChangePasswordRequest;
import com.myproject.S2dcms.dto.auth.LoginRequest;
import com.myproject.S2dcms.dto.email.EmailMessage;
import com.myproject.S2dcms.dto.verification.ForgotPasswordRequest;
import com.myproject.S2dcms.dto.verification.ResetPasswordRequest;
import com.myproject.S2dcms.model.Department;
import com.myproject.S2dcms.model.RefreshToken;
import com.myproject.S2dcms.model.Role;
import com.myproject.S2dcms.model.Student;
import com.myproject.S2dcms.repository.DepartmentRepo;
import com.myproject.S2dcms.repository.RefreshTokenRepository;
import com.myproject.S2dcms.repository.StudentRepo;
import com.myproject.S2dcms.securityConfig.JwtUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sendinblue.ApiException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService{

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenService tokenService;
    private final JwtUtil jwtUtil;
    private final StudentRepo studentRepository;
    private final DepartmentRepo departmentRepository;
    private final UserActionService userActionService;
    private final PasswordEncoder passwordEncoder;
    private final TokenLimitService tokenLimitService;
    private final EmailProducerService emailProducerService;
    private final UserLookupService userLookupService;


    public AuthService(RefreshTokenRepository refreshTokenRepository, RefreshTokenService tokenService, JwtUtil jwtUtil, StudentRepo studentRepository, DepartmentRepo departmentRepository, UserActionService userActionService, PasswordEncoder passwordEncoder, TokenLimitService tokenLimitService, EmailProducerService emailProducerService, UserLookupService userLookupService) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenService = tokenService;
        this.jwtUtil = jwtUtil;
        this.studentRepository = studentRepository;
        this.departmentRepository = departmentRepository;
        this.userActionService = userActionService;
        this.passwordEncoder = passwordEncoder;
        this.tokenLimitService = tokenLimitService;
        this.emailProducerService = emailProducerService;
        this.userLookupService = userLookupService;
    }

    public AuthResponse login(LoginRequest dto, HttpServletResponse response) {

        String email = dto.getEmail();
        UserLookupService.UserResult userResult = userLookupService.findByEmail(email);

        if (userResult == null) {
            throw new InvalidPasswordException("Invalid credentials");
        }

        String rateLimitAction = userResult.userType() == UserLookupService.UserType.STUDENT 
            ? "STUDENT_LOGIN" 
            : "DEPARTMENT_LOGIN";

        userActionService.checkRateLimit(email, rateLimitAction);

        if (!passwordEncoder.matches(dto.getPassword(), userResult.getPassword())) {
            throw new InvalidPasswordException("Invalid credentials");
        }

        if (userResult.userType() == UserLookupService.UserType.STUDENT && !userResult.getStudent().isEmailVerified()) {
            throw new EmailVerificationException("Email not verified");
        }

        userActionService.resetRateLimit(email, rateLimitAction);

        String accessToken;
        RefreshToken refreshToken;

        if (userResult.userType() == UserLookupService.UserType.STUDENT) {
            tokenLimitService.manageTokenLimitForStudent(userResult.getStudent());
            accessToken = jwtUtil.generateToken(userResult.getEmail(), userResult.getRole());
            refreshToken = tokenService.createRefreshTokenForStudent(userResult.getStudent());
        } else {
            tokenLimitService.manageTokenLimitForDepartment(userResult.getDepartment());
            accessToken = jwtUtil.generateToken(userResult.getEmail(), userResult.getRole());
            refreshToken = tokenService.createRefreshTokenForDepartment(userResult.getDepartment());
        }

        // Set HttpOnly cookies for security - NO TOKENS IN RESPONSE BODY
        setAuthCookies(response, accessToken, refreshToken.getToken());

        // Return minimal response with user info - tokens are only in cookies for security
        AuthResponse authResponse = new AuthResponse(userResult.getEmail(), userResult.getRole().name());
        return authResponse;
    }

   private void setAuthCookies(
        HttpServletResponse response,
        String accessToken,
        String refreshToken
) {
    boolean isProduction = isProductionEnvironment();

    String sameSiteValue = isProduction ? "None" : "Lax";

    // Access token cookie - No Domain for localhost/proxy
    String accessCookie = String.format(
            "accessToken=%s; Max-Age=%d; Path=/; HttpOnly; SameSite=%s%s",
            accessToken,
            15 * 60,
            sameSiteValue,
            isProduction ? "; Secure" : ""
    );

    // Refresh token cookie - No Domain for localhost/proxy
    String refreshCookie = String.format(
            "refreshToken=%s; Max-Age=%d; Path=/; HttpOnly; SameSite=%s%s",
            refreshToken,
            24 * 60 * 60,
            sameSiteValue,
            isProduction ? "; Secure" : ""
    );

    response.addHeader("Set-Cookie", accessCookie);
    response.addHeader("Set-Cookie", refreshCookie);
}

    private boolean isProductionEnvironment() {
        // Check if we're running in production (Render, Vercel, etc.)
        // You can also use environment variables: System.getenv("ENVIRONMENT")
        String env = System.getenv("ENVIRONMENT");
        return "production".equalsIgnoreCase(env) || 
               "render".equalsIgnoreCase(env) ||
               "vercel".equalsIgnoreCase(env);
    }

    private void clearAuthCookies(HttpServletResponse response) {
        boolean isProduction = isProductionEnvironment();
        // SameSite attribute: Lax for development, None for production (cross-origin)
        String sameSiteValue = isProduction ? "None" : "Lax";
        
        // Clear access token cookie
        jakarta.servlet.http.Cookie accessCookie = new jakarta.servlet.http.Cookie("accessToken", "");
        accessCookie.setHttpOnly(true);
        accessCookie.setSecure(isProduction);
        accessCookie.setPath("/");
        accessCookie.setMaxAge(0); // Setting maxAge to 0 deletes the cookie
        accessCookie.setAttribute("SameSite", sameSiteValue);
        response.addCookie(accessCookie);

        // Clear refresh token cookie
        jakarta.servlet.http.Cookie refreshCookie = new jakarta.servlet.http.Cookie("refreshToken", "");
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(isProduction);
        refreshCookie.setPath("/");
        refreshCookie.setMaxAge(0); // Setting maxAge to 0 deletes the cookie
        refreshCookie.setAttribute("SameSite", sameSiteValue);
        response.addCookie(refreshCookie);
    }

    private String getRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if ("refreshToken".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }


    /**
     * Cookie-only rotation. The client sends no payload; the presented token is the
     * {@code refreshToken} cookie.
     */
    public AuthResponse refreshToken(HttpServletResponse response, HttpServletRequest httpRequest) {

        // The refresh token comes from the cookie, never from a request body
        String refreshTokenValue = getRefreshTokenFromCookie(httpRequest);
        
        if (refreshTokenValue == null) {
            throw new RefreshTokenException("No refresh token cookie found");
        }

    RefreshToken oldToken = refreshTokenRepository.findByToken(refreshTokenValue)
            .orElseThrow(() -> new RefreshTokenException("Invalid refresh token"));

    if (oldToken.isRevoked()) {
        throw new RefreshTokenException("Refresh token revoked");
    }

    if (oldToken.getExpiryDate().isBefore(Instant.now())) {
        throw new RefreshTokenException("Refresh token expired");
    }

    /*  DETECT USER TYPE */
    String email;
    Role role;

    if (oldToken.getStudent() != null) {
        email = oldToken.getStudent().getEmail();
        role = oldToken.getStudent().getRole();
    } else if (oldToken.getDepartment() != null) {
        email = oldToken.getDepartment().getEmail();
        role = oldToken.getDepartment().getRole();
    } else {
        throw new RefreshTokenException("Invalid token owner");
    }

    /* ROTATE TOKEN */
    RefreshToken newToken = tokenService.rotateToken(oldToken);

    /* GENERATE NEW ACCESS TOKEN */
    String newAccessToken = jwtUtil.generateToken(email, role);

    // Update cookies with new tokens - NO TOKENS IN RESPONSE BODY
    setAuthCookies(response, newAccessToken, newToken.getToken());

    // Return minimal response with user info - tokens are only in cookies for security
    AuthResponse authResponse = new AuthResponse(email, role.name());
    return authResponse;
    }

    public void forgotPassword(ForgotPasswordRequest request) {

        userActionService.checkRateLimit(request.getEmail(), "FORGOT_PASSWORD");

        String token = UUID.randomUUID().toString();
        UserLookupService.UserResult userResult = userLookupService.findByEmail(request.getEmail());

        if (userResult == null) {
            throw new DepartmentNotFoundException("User not found");
        }

        if (userResult.userType() == UserLookupService.UserType.STUDENT) {
            Student student = userResult.getStudent();
            student.setPasswordResetToken(token);
            student.setPasswordResetTokenExpiry(LocalDateTime.now().plusHours(24));
            studentRepository.save(student);

            EmailMessage emailMessage = new EmailMessage(
                student.getEmail(),
                "Reset your password",
                "PASSWORD_RESET_STUDENT",
                token,
                student.getName()
            );
            emailProducerService.sendEmailMessage(emailMessage);
        } else {
            Department department = userResult.getDepartment();
            department.setPasswordResetToken(token);
            department.setPasswordResetTokenExpiry(LocalDateTime.now().plusHours(24));
            departmentRepository.save(department);

            EmailMessage emailMessage = new EmailMessage(
                department.getEmail(),
                "Reset your password",
                "PASSWORD_RESET_DEPARTMENT",
                token,
                department.getDepartmentName()
            );
            emailProducerService.sendEmailMessage(emailMessage);
        }
    }

    public void resetPassword(ResetPasswordRequest request) {

        String token = request.getToken();
        UserLookupService.UserResult userResult = userLookupService.findByPasswordResetToken(token);

        if (userResult == null) {
            throw new RefreshTokenException("Invalid token");
        }

        if (userResult.userType() == UserLookupService.UserType.STUDENT) {
            Student student = userResult.getStudent();

            if (student.getPasswordResetTokenExpiry() == null ||
                    student.getPasswordResetTokenExpiry().isBefore(LocalDateTime.now())) {
                throw new RefreshTokenException("Token expired");
            }

            student.setPassword(passwordEncoder.encode(request.getNewPassword()));
            student.setPasswordResetToken(null);
            student.setPasswordResetTokenExpiry(null);
            studentRepository.save(student);
        } else {
            Department department = userResult.getDepartment();

            if (department.getPasswordResetTokenExpiry() == null ||
                    department.getPasswordResetTokenExpiry().isBefore(LocalDateTime.now())) {
                throw new RefreshTokenException("Token expired");
            }

            department.setPassword(passwordEncoder.encode(request.getNewPassword()));
            department.setPasswordResetToken(null);
            department.setPasswordResetTokenExpiry(null);
            departmentRepository.save(department);
        }
    }



    // The derived deleteBy... queries below need a transaction, otherwise JPA refuses the
    // 'remove' call while password rotation is in flight.
    @Transactional
    public void changePassword(String email, ChangePasswordRequest request) {

        UserLookupService.UserResult userResult = userLookupService.findByEmail(email);

        if (userResult == null) {
            throw new DepartmentNotFoundException("User not found");
        }

        if (!passwordEncoder.matches(request.getOldPassword(), userResult.getPassword())) {
            throw new InvalidPasswordException("Old password is incorrect");
        }

        if (userResult.userType() == UserLookupService.UserType.STUDENT) {
            Student student = userResult.getStudent();
            student.setPassword(passwordEncoder.encode(request.getNewPassword()));
            studentRepository.save(student);
            refreshTokenRepository.deleteByStudent(student);
        } else {
            Department department = userResult.getDepartment();
            department.setPassword(passwordEncoder.encode(request.getNewPassword()));
            departmentRepository.save(department);
            refreshTokenRepository.deleteByDepartment(department);
        }
    }



    /**
     * Cookie-only logout. Revokes the refresh token held in the cookie (if any) and
     * expires both auth cookies.
     */
    public void logout(HttpServletResponse response, HttpServletRequest httpRequest) {

        // The refresh token comes from the cookie, never from a request body
        String refreshTokenValue = getRefreshTokenFromCookie(httpRequest);
        
        if (refreshTokenValue != null) {
            refreshTokenRepository.findByToken(refreshTokenValue)
                    .ifPresent(token -> {
                        token.setRevoked(true);
                        refreshTokenRepository.save(token);
                    });
        }

        // Clear auth cookies
        clearAuthCookies(response);
    }

}