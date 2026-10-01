package com.myproject.S2dcms.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myproject.S2dcms.dto.auth.ChangePasswordRequest;
import com.myproject.S2dcms.dto.auth.LoginRequest;
import com.myproject.S2dcms.model.Department;
import com.myproject.S2dcms.model.Role;
import com.myproject.S2dcms.model.Student;
import com.myproject.S2dcms.repository.DepartmentRepo;
import com.myproject.S2dcms.repository.RefreshTokenRepository;
import com.myproject.S2dcms.repository.StudentRepo;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the cookie based authentication flow.
 *
 * <p>The API no longer returns tokens in the JSON body:
 * <ul>
 *   <li>login / refresh write HttpOnly {@code accessToken} + {@code refreshToken} cookies</li>
 *   <li>{@code JwtAuthFilter} reads the access token from the cookie only - there is no
 *       {@code Authorization} header fallback anymore</li>
 *   <li>state changing calls additionally need the {@code X-XSRF-TOKEN} header that the SPA
 *       mirrors from the readable {@code XSRF-TOKEN} cookie</li>
 *   <li>logout clears the cookies and revokes the refresh token in the database</li>
 * </ul>
 *
 * <p>Full stack: MockMvc -&gt; SecurityFilterChain -&gt; JwtAuthFilter -&gt; Controller
 * -&gt; Service -&gt; H2.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIntegrationTest {

    private static final String STUDENT_EMAIL = "test@student.com";
    private static final String DEPARTMENT_EMAIL = "test@dept.com";
    private static final String RAW_PASSWORD = "password123";
    private static final String ACCESS_COOKIE = "accessToken";
    private static final String REFRESH_COOKIE = "refreshToken";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StudentRepo studentRepository;

    @Autowired
    private DepartmentRepo departmentRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private Department testDepartment;
    private Student testStudent;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        studentRepository.deleteAll();
        departmentRepository.deleteAll();

        testDepartment = new Department();
        testDepartment.setEmail(DEPARTMENT_EMAIL);
        testDepartment.setPassword(passwordEncoder.encode(RAW_PASSWORD));
        testDepartment.setDepartmentName("Test Department");
        testDepartment.setRole(Role.DEPARTMENT);
        testDepartment = departmentRepository.save(testDepartment);

        testStudent = new Student();
        testStudent.setEmail(STUDENT_EMAIL);
        testStudent.setPassword(passwordEncoder.encode(RAW_PASSWORD));
        testStudent.setName("Test Student");
        testStudent.setRegNo("TEST001");
        testStudent.setDepartment(testDepartment);
        testStudent.setRole(Role.STUDENT);
        testStudent.setEmailVerified(true);
        testStudent = studentRepository.save(testStudent);
    }

    // Login: tokens travel as HttpOnly cookies, never inside the body
    

    @Test
    void loginAsStudent_setsHttpOnlyCookiePairAndHidesTokensFromBody() throws Exception {
        MockHttpServletResponse response = login(STUDENT_EMAIL, RAW_PASSWORD);

        String accessToken = setCookieValue(response, ACCESS_COOKIE);
        String refreshToken = setCookieValue(response, REFRESH_COOKIE);
        assertNotNull(accessToken, "an access token cookie must be issued");
        assertNotNull(refreshToken, "a refresh token cookie must be issued");

        List<String> cookies = setCookieHeaders(response);
        assertTrue(cookies.stream().anyMatch(c -> c.startsWith(ACCESS_COOKIE + "=")
                        && c.contains("HttpOnly") && c.contains("Path=/")),
                "access token cookie must be HttpOnly and scoped to /: " + cookies);
        assertTrue(cookies.stream().anyMatch(c -> c.startsWith(REFRESH_COOKIE + "=")
                        && c.contains("HttpOnly") && c.contains("Path=/")),
                "refresh token cookie must be HttpOnly and scoped to /: " + cookies);

        assertNoTokensInBody(response, accessToken, refreshToken);

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals(STUDENT_EMAIL, body.path("email").asText());
        assertEquals("STUDENT", body.path("role").asText());
    }

    @Test
    void loginAsDepartment_setsCookiePairForDepartmentRole() throws Exception {
        MockHttpServletResponse response = login(DEPARTMENT_EMAIL, RAW_PASSWORD);

        String accessToken = setCookieValue(response, ACCESS_COOKIE);
        String refreshToken = setCookieValue(response, REFRESH_COOKIE);
        assertNotNull(accessToken);
        assertNotNull(refreshToken);
        assertNoTokensInBody(response, accessToken, refreshToken);

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals(DEPARTMENT_EMAIL, body.path("email").asText());
        assertEquals("DEPARTMENT", body.path("role").asText());
    }

    @Test
    void loginWithWrongPassword_returns401AndIssuesNoCookies() throws Exception {
        MockHttpServletResponse response = login(DEPARTMENT_EMAIL, "wrongpassword",
                HttpServletResponse.SC_UNAUTHORIZED);

        assertNull(setCookieValue(response, ACCESS_COOKIE), "no session may start on a failed login");
        assertNull(setCookieValue(response, REFRESH_COOKIE));
    }

    @Test
    void loginWithUnknownUser_returns401AndIssuesNoCookies() throws Exception {
        MockHttpServletResponse response = login("ghost@student.com", RAW_PASSWORD,
                HttpServletResponse.SC_UNAUTHORIZED);

        assertNull(setCookieValue(response, ACCESS_COOKIE));
        assertNull(setCookieValue(response, REFRESH_COOKIE));
    }

    @Test
    void loginWithUnverifiedStudent_returns401BeforeCookiesAreWritten() throws Exception {
        testStudent.setEmailVerified(false);
        studentRepository.save(testStudent);

        MockHttpServletResponse response = login(STUDENT_EMAIL, RAW_PASSWORD,
                HttpServletResponse.SC_UNAUTHORIZED);

        assertNull(setCookieValue(response, ACCESS_COOKIE));
    }

    // Protected calls: the access token cookie is the only credential
    

    @Test
    void getMeWithAccessTokenCookie_returnsAuthenticatedIdentity() throws Exception {
        String accessToken = setCookieValue(login(STUDENT_EMAIL, RAW_PASSWORD), ACCESS_COOKIE);

        mockMvc.perform(get("/api/auth/me").cookie(new Cookie(ACCESS_COOKIE, accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(STUDENT_EMAIL))
                .andExpect(jsonPath("$.role").value("STUDENT"));
    }

    @Test
    void getMeWithoutCookie_isRejectedWithJson401SoTheSpaCanRefresh() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void getMeWithAuthorizationHeaderOnly_isRejectedBecauseAuthIsCookieOnly() throws Exception {
        String accessToken = setCookieValue(login(STUDENT_EMAIL, RAW_PASSWORD), ACCESS_COOKIE);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getMeWithTamperedAccessTokenCookie_isRejected() throws Exception {
        mockMvc.perform(get("/api/auth/me").cookie(new Cookie(ACCESS_COOKIE, "not-a-real-jwt")))
                .andExpect(status().isUnauthorized());
    }

    
    // Refresh token rotation
    

    @Test
    void refreshToken_rotatesTheCookiePairAndRevokesTheOldToken() throws Exception {
        String oldRefreshToken = setCookieValue(login(STUDENT_EMAIL, RAW_PASSWORD), REFRESH_COOKIE);

        MockHttpServletResponse response = mockMvc.perform(post("/api/auth/refresh-token")
                        .cookie(new Cookie(REFRESH_COOKIE, oldRefreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(STUDENT_EMAIL))
                .andReturn().getResponse();

        String newAccessToken = setCookieValue(response, ACCESS_COOKIE);
        String newRefreshToken = setCookieValue(response, REFRESH_COOKIE);
        assertNotNull(newAccessToken, "a fresh access token cookie must be issued");
        assertNotNull(newRefreshToken);
        assertNotEquals(oldRefreshToken, newRefreshToken, "the refresh token must rotate");
        assertNoTokensInBody(response, newAccessToken, newRefreshToken);

        assertTrue(refreshTokenRepository.findByToken(oldRefreshToken).orElseThrow().isRevoked(),
                "the presented refresh token must be revoked after rotation");
        assertFalse(refreshTokenRepository.findByToken(newRefreshToken).orElseThrow().isRevoked());

        mockMvc.perform(post("/api/auth/refresh-token")
                        .cookie(new Cookie(REFRESH_COOKIE, oldRefreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenWithoutCookie_isRejected() throws Exception {
        mockMvc.perform(post("/api/auth/refresh-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenWithUnknownCookieValue_isRejected() throws Exception {
        mockMvc.perform(post("/api/auth/refresh-token")
                        .cookie(new Cookie(REFRESH_COOKIE, "00000000-0000-0000-0000-000000000000")))
                .andExpect(status().isUnauthorized());
    }

    // Logout: cookies expire and the refresh token is revoked
    

    @Test
    void logout_revokesRefreshTokenAndClearsBothAuthCookies() throws Exception {
        String refreshToken = setCookieValue(login(STUDENT_EMAIL, RAW_PASSWORD), REFRESH_COOKIE);

        MockHttpServletResponse logoutResponse = mockMvc.perform(post("/api/auth/logout")
                        .cookie(new Cookie(REFRESH_COOKIE, refreshToken)))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();

        List<String> cleared = setCookieHeaders(logoutResponse);
        assertTrue(cleared.stream().anyMatch(c -> c.startsWith(ACCESS_COOKIE + "=") && c.contains("Max-Age=0")),
                "logout must expire the access token cookie: " + cleared);
        assertTrue(cleared.stream().anyMatch(c -> c.startsWith(REFRESH_COOKIE + "=") && c.contains("Max-Age=0")),
                "logout must expire the refresh token cookie: " + cleared);

        assertTrue(refreshTokenRepository.findByToken(refreshToken).orElseThrow().isRevoked(),
                "logout must revoke the presented refresh token");
    }

    // CSRF: cookie auth alone must not be enough for writes

    @Test
    void changePassword_requiresCsrfTokenAndRejectsRequestWithoutIt() throws Exception {
        MockHttpServletResponse loginResponse = login(STUDENT_EMAIL, RAW_PASSWORD);
        String accessToken = setCookieValue(loginResponse, ACCESS_COOKIE);
        String refreshToken = setCookieValue(loginResponse, REFRESH_COOKIE);

        // A safe GET seeds the readable XSRF-TOKEN cookie the SPA mirrors into a header
        MockHttpServletResponse seedResponse = mockMvc.perform(get("/api/auth/me")
                        .cookie(new Cookie(ACCESS_COOKIE, accessToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        String xsrfToken = setCookieValue(seedResponse, XSRF_COOKIE);
        assertNotNull(xsrfToken, "a readable XSRF-TOKEN cookie must be issued to the SPA");

        // document.cookie is unreadable cross-origin, so GET /api/auth/csrf serves the token
        // as JSON - the same raw value the X-XSRF-TOKEN response header carries, which the
        // SPA mirrors back into the X-XSRF-TOKEN request header.
        String csrfBody = mockMvc.perform(get("/api/auth/csrf")
                        .cookie(new Cookie(XSRF_COOKIE, xsrfToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String bootstrapToken = objectMapper.readTree(csrfBody).get("token").asText();

        ChangePasswordRequest changeRequest = new ChangePasswordRequest();
        changeRequest.setOldPassword(RAW_PASSWORD);
        changeRequest.setNewPassword("brandNewPassword1");
        String payload = objectMapper.writeValueAsString(changeRequest);

        // JwtAuthFilter authenticates before CsrfFilter, so a signed-in caller that omits the
        // header gets an actionable 403 - never a 401 the SPA would misread as an expired session.
        mockMvc.perform(post("/api/user/change-password")
                        .cookie(new Cookie(ACCESS_COOKIE, accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CSRF token missing or invalid"));

        // The cookie alone is not enough - the SPA must present the token the server published.
        // With masking enabled that is the bootstrap value, NOT the raw cookie value: the
        // cookie carries the raw secret for comparison, while what the client may echo back is
        // the masked form. Presenting the raw cookie value in the header is correctly rejected.
        mockMvc.perform(post("/api/user/change-password")
                        .cookie(new Cookie(ACCESS_COOKIE, accessToken))
                        .cookie(new Cookie(XSRF_COOKIE, xsrfToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CSRF token missing or invalid"));

        assertFalse(passwordEncoder.matches("brandNewPassword1", currentStoredPassword()),
                "the password must be untouched when the CSRF token is missing");

        // The raw cookie value replayed as the header is NOT an accepted token: only the
        // masked form the server published may come back.
        mockMvc.perform(post("/api/user/change-password")
                        .cookie(new Cookie(ACCESS_COOKIE, accessToken))
                        .cookie(new Cookie(XSRF_COOKIE, xsrfToken))
                        .header("X-XSRF-TOKEN", xsrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CSRF token missing or invalid"));

        assertFalse(passwordEncoder.matches("brandNewPassword1", currentStoredPassword()),
                "replaying the raw cookie value must not authenticate the write");

        mockMvc.perform(post("/api/user/change-password")
                        .cookie(new Cookie(ACCESS_COOKIE, accessToken))
                        .cookie(new Cookie(XSRF_COOKIE, xsrfToken))
                        .header("X-XSRF-TOKEN", bootstrapToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        assertTrue(passwordEncoder.matches("brandNewPassword1", currentStoredPassword()),
                "the password changes once cookie + CSRF header are presented together");

        // Cross-origin path: the bootstrap token is accepted exactly like the response-header
        // value the deployed SPA captures (JwtAuthFilter is stateless, so the access cookie
        // from login still authenticates this second write).
        ChangePasswordRequest crossOriginRequest = new ChangePasswordRequest();
        crossOriginRequest.setOldPassword("brandNewPassword1");
        crossOriginRequest.setNewPassword("crossOriginPassword1");
        mockMvc.perform(post("/api/user/change-password")
                        .cookie(new Cookie(ACCESS_COOKIE, accessToken))
                        .cookie(new Cookie(XSRF_COOKIE, xsrfToken))
                        .header("X-XSRF-TOKEN", bootstrapToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crossOriginRequest)))
                .andExpect(status().isOk());

        assertTrue(passwordEncoder.matches("crossOriginPassword1", currentStoredPassword()),
                "the bootstrap token from GET /api/auth/csrf must be accepted in the header");

        assertTrue(refreshTokenRepository.findByToken(refreshToken).isEmpty(),
                "changing the password must invalidate every stored refresh token");
    }

    @Test
    void changePasswordWithoutAccessTokenCookie_isRejectedWith401() throws Exception {
        mockMvc.perform(post("/api/user/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"" + RAW_PASSWORD + "\",\"newPassword\":\"whatever123\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ==================================================================
    // Attachment downloads stay public so AttachmentModal can render them
    // ==================================================================

    @Test
    void uploadedAttachmentsAreNotBehindTheAuthenticationWall() throws Exception {
        // AttachmentModal resolves /uploads/** directly through the assetUrl() helper
        mockMvc.perform(get("/uploads/missing-evidence.png"))
                .andExpect(result -> assertNotEquals(HttpServletResponse.SC_UNAUTHORIZED,
                        result.getResponse().getStatus(), "uploads must not require an access cookie"));
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private MockHttpServletResponse login(String email, String password) throws Exception {
        return login(email, password, HttpServletResponse.SC_OK);
    }

    private MockHttpServletResponse login(String email, String password, int expectedStatus) throws Exception {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword(password);

        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse();
    }

    private String currentStoredPassword() {
        return studentRepository.findByEmailIgnoreCase(STUDENT_EMAIL).orElseThrow().getPassword();
    }

    /**
     * Both cookie styles used by the app end up here: the auth cookies are written with
     * {@code addHeader("Set-Cookie", ...)} while the CSRF and logout cookies go through
     * {@code addCookie(...)}.
     */
    private static List<String> setCookieHeaders(MockHttpServletResponse response) {
        List<String> headers = new ArrayList<>(response.getHeaders("Set-Cookie"));
        for (Cookie cookie : response.getCookies()) {
            StringBuilder rendered = new StringBuilder(cookie.getName() + "=" + cookie.getValue());
            rendered.append("; Max-Age=").append(cookie.getMaxAge());
            if (cookie.getPath() != null) {
                rendered.append("; Path=").append(cookie.getPath());
            }
            if (cookie.isHttpOnly()) {
                rendered.append("; HttpOnly");
            }
            if (cookie.getSecure()) {
                rendered.append("; Secure");
            }
            headers.add(rendered.toString());
        }
        return headers;
    }

    private static String setCookieValue(MockHttpServletResponse response, String name) {
        String prefix = name + "=";
        return setCookieHeaders(response).stream()
                .map(header -> header.split(";", 2)[0].trim())
                .filter(pair -> pair.startsWith(prefix))
                .map(pair -> pair.substring(prefix.length()))
                .findFirst()
                .orElse(null);
    }

    private static void assertNoTokensInBody(MockHttpServletResponse response,
                                             String accessToken,
                                             String refreshToken) throws Exception {
        String body = response.getContentAsString();
        assertFalse(body.contains(accessToken), "the access token must never appear in the response body");
        assertFalse(body.contains(refreshToken), "the refresh token must never appear in the response body");
    }
}




