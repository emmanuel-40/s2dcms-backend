package com.myproject.S2dcms.Service;

import com.myproject.S2dcms.Exception.EmailVerificationException;
import com.myproject.S2dcms.Exception.InvalidPasswordException;
import com.myproject.S2dcms.dto.auth.AuthResponse;
import com.myproject.S2dcms.dto.auth.LoginRequest;
import com.myproject.S2dcms.model.Department;
import com.myproject.S2dcms.model.RefreshToken;
import com.myproject.S2dcms.model.Role;
import com.myproject.S2dcms.model.Student;
import com.myproject.S2dcms.repository.DepartmentRepo;
import com.myproject.S2dcms.repository.RefreshTokenRepository;
import com.myproject.S2dcms.repository.StudentRepo;
import com.myproject.S2dcms.securityConfig.JwtUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthService
 * Tests each method in isolation using mocks
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private StudentRepo studentRepository;

    @Mock
    private DepartmentRepo departmentRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private RefreshTokenService tokenService;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private UserActionService userActionService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenLimitService tokenLimitService;

    @Mock
    private EmailProducerService emailProducerService;

    @Mock
    private UserLookupService userLookupService;

    @Mock
    private HttpServletResponse response;

    @InjectMocks
    private AuthService authService;

    private Student testStudent;
    private Department testDepartment;
    private LoginRequest loginRequest;

    @BeforeEach
    void setUp() {
        testStudent = new Student();
        testStudent.setId(1L);
        testStudent.setEmail("student@test.com");
        testStudent.setPassword("encodedPassword");
        testStudent.setRole(Role.STUDENT);
        testStudent.setEmailVerified(true);

        testDepartment = new Department();
        testDepartment.setId(1L);
        testDepartment.setEmail("dept@test.com");
        testDepartment.setPassword("encodedPassword");
        testDepartment.setRole(Role.DEPARTMENT);

        loginRequest = new LoginRequest();
        loginRequest.setEmail("student@test.com");
        loginRequest.setPassword("rawPassword");
    }

    @Test
    void testLogin_Success_Student() {
        // Arrange
        when(userLookupService.findByEmail("student@test.com"))
            .thenReturn(new UserLookupService.UserResult(testStudent, UserLookupService.UserType.STUDENT));
        when(passwordEncoder.matches("rawPassword", "encodedPassword"))
            .thenReturn(true);
        when(jwtUtil.generateToken(anyString(), any(Role.class)))
            .thenReturn("jwtToken");
        RefreshToken studentRefreshToken = new RefreshToken();
        studentRefreshToken.setToken("refreshTokenValue");
        when(tokenService.createRefreshTokenForStudent(any(Student.class)))
            .thenReturn(studentRefreshToken);

        // Act
        AuthResponse authResponse = authService.login(loginRequest, response);

        // Assert - the body carries identity only; AuthResponse has no token fields to leak
        assertNotNull(authResponse);
        assertEquals("student@test.com", authResponse.getEmail());
        assertEquals("STUDENT", authResponse.getRole());

        // ...they are delivered as HttpOnly cookies instead
        ArgumentCaptor<String> cookies = ArgumentCaptor.forClass(String.class);
        verify(response, times(2)).addHeader(eq("Set-Cookie"), cookies.capture());
        assertTrue(cookies.getAllValues().stream()
                .anyMatch(c -> c.startsWith("accessToken=jwtToken;") && c.contains("HttpOnly")));
        assertTrue(cookies.getAllValues().stream()
                .anyMatch(c -> c.startsWith("refreshToken=refreshTokenValue;") && c.contains("HttpOnly")));

        verify(userActionService).checkRateLimit("student@test.com", "STUDENT_LOGIN");
        verify(userActionService).resetRateLimit("student@test.com", "STUDENT_LOGIN");
    }

    @Test
    void testLogin_Student_NotVerified() {
        // Arrange
        testStudent.setEmailVerified(false);
        when(userLookupService.findByEmail("student@test.com"))
            .thenReturn(new UserLookupService.UserResult(testStudent, UserLookupService.UserType.STUDENT));
        when(passwordEncoder.matches("rawPassword", "encodedPassword"))
            .thenReturn(true);

        // Act & Assert
        assertThrows(EmailVerificationException.class, () -> authService.login(loginRequest, response));
    }

    @Test
    void testLogin_InvalidPassword() {
        // Arrange
        when(userLookupService.findByEmail("student@test.com"))
            .thenReturn(new UserLookupService.UserResult(testStudent, UserLookupService.UserType.STUDENT));
        when(passwordEncoder.matches("rawPassword", "encodedPassword"))
            .thenReturn(false);

        // Act & Assert
        assertThrows(InvalidPasswordException.class, () -> authService.login(loginRequest, response));
    }

    @Test
    void testLogin_Success_Department() {
        // Arrange
        loginRequest.setEmail("dept@test.com");
        when(userLookupService.findByEmail("dept@test.com"))
            .thenReturn(new UserLookupService.UserResult(testDepartment, UserLookupService.UserType.DEPARTMENT));
        when(passwordEncoder.matches("rawPassword", "encodedPassword"))
            .thenReturn(true);
        when(jwtUtil.generateToken(anyString(), any(Role.class)))
            .thenReturn("jwtToken");
        RefreshToken departmentRefreshToken = new RefreshToken();
        departmentRefreshToken.setToken("deptRefreshTokenValue");
        when(tokenService.createRefreshTokenForDepartment(any(Department.class)))
            .thenReturn(departmentRefreshToken);

        // Act
        AuthResponse authResponse = authService.login(loginRequest, response);

        // Assert - the body stays token free (there is nowhere to put a token), the cookies carry the credentials
        assertNotNull(authResponse);
        assertEquals("dept@test.com", authResponse.getEmail());
        assertEquals("DEPARTMENT", authResponse.getRole());

        ArgumentCaptor<String> cookies = ArgumentCaptor.forClass(String.class);
        verify(response, times(2)).addHeader(eq("Set-Cookie"), cookies.capture());
        assertTrue(cookies.getAllValues().stream()
                .anyMatch(c -> c.startsWith("accessToken=jwtToken;") && c.contains("HttpOnly")));
        assertTrue(cookies.getAllValues().stream()
                .anyMatch(c -> c.startsWith("refreshToken=deptRefreshTokenValue;") && c.contains("HttpOnly")));
    }
}
