package com.myproject.S2dcms.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myproject.S2dcms.Service.UserLookupService;
import com.myproject.S2dcms.model.Department;
import com.myproject.S2dcms.model.Student;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for { IdempotencyInterceptor}.
 * 
 * Uses a real {@link ObjectMapper} (not mocked) so state serialization is
 * realistic. Uses fresh request/response objects for each scenario to avoid
 * cross-test contamination. Mocks { RedisTemplate#execute} for Lua scripts
 * and { UserLookupService} for user identity resolution.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdempotencyUnitTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private UserLookupService userLookupService;

    @Mock
    private Student mockStudent;

    @Mock
    private Department mockDepartment;

    private ObjectMapper objectMapper;
    private IdempotencyInterceptor interceptor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Mock user lookup: email -> student with id 42
        when(mockStudent.getId()).thenReturn(42L);
        UserLookupService.UserResult studentResult = new UserLookupService.UserResult(mockStudent, UserLookupService.UserType.STUDENT);
        when(userLookupService.findByEmail("student@test.com")).thenReturn(studentResult);

        interceptor = new IdempotencyInterceptor(redisTemplate, objectMapper, userLookupService);
        SecurityContextHolder.clearContext();
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private ContentCachingRequestWrapper newRequest(String method, String uri, String body, String key) {
        MockHttpServletRequest raw = new MockHttpServletRequest();
        raw.setMethod(method);
        raw.setRequestURI(uri);
        if (body != null) {
            raw.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        if (key != null) {
            raw.addHeader("Idempotency-Key", key);
        }
        return new ContentCachingRequestWrapper(raw);
    }

    private ContentCachingResponseWrapper newResponse() {
        return new ContentCachingResponseWrapper(new MockHttpServletResponse());
    }

    private void setAuthenticatedUser(String email) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(email, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void mockClaim(Long result) {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(result);
    }

    private void mockGet(String json) {
        when(valueOperations.get(anyString())).thenReturn(json);
    }

    /* SHA-256 helper matching the interceptor's computation */
    private static String sha256Hex(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 1. Missing key                                                     */
    /* ------------------------------------------------------------------ */

    @Test
    void missingIdempotencyKey_proceeds() throws Exception {
        setAuthenticatedUser("student@test.com");
        ContentCachingRequestWrapper request = newRequest("POST", "/api/students/complaints", "{\"title\":\"x\"}", null);
        ContentCachingResponseWrapper response = newResponse();

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result);
        assertEquals(200, response.getStatus());
        verify(redisTemplate, never()).execute(any(), anyList(), any(Object[].class));
    }

    /* ------------------------------------------------------------------ */
    /* 2. New key                                                         */
    /* ------------------------------------------------------------------ */

    @Test
    void newIdempotencyKey_claimsProcessing_returnsTrue() throws Exception {
        setAuthenticatedUser("student@test.com");
        ContentCachingRequestWrapper request = newRequest("POST", "/api/students/complaints",
                "{\"title\":\"broken chair\"}", "key-001");
        ContentCachingResponseWrapper response = newResponse();

        mockClaim(1L);
        mockGet(null);

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result);
        assertEquals(200, response.getStatus());
        assertNotNull(request.getAttribute("idempotency.key"));
        assertNotNull(request.getAttribute("idempotency.owner"));

        // Verify the key is scoped by user ID (database ID) + method + uri + key
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList(
                        "idempotency:student:42:POST:/api/students/complaints:key-001")),
                any(Object[].class));
    }

    /* ------------------------------------------------------------------ */
    /* 3. Duplicate key (COMPLETED + same body) → replay                  */
    /* ------------------------------------------------------------------ */

    @Test
    void duplicateKeySameBody_replaysOriginalResponse() throws Exception {
        setAuthenticatedUser("student@test.com");
        String body = "";
        ContentCachingRequestWrapper request = newRequest("POST", "/api/students/complaints", body, "key-dup");
        ContentCachingResponseWrapper response = newResponse();

        String bodyHash = sha256Hex(body);

        Map<String, Object> completedState = new HashMap<>();
        completedState.put("state", "COMPLETED");
        completedState.put("bodyHash", bodyHash);
        completedState.put("owner", "owner-1");
        completedState.put("status", 201);
        completedState.put("body", "{\"id\":42}");
        completedState.put("contentType", "application/json");
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Custom", "value1");
        completedState.put("headers", objectMapper.writeValueAsString(headers));

        mockClaim(0L);
        mockGet(objectMapper.writeValueAsString(completedState));

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        assertEquals(201, response.getStatus());
        String bodyStr = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(bodyStr.contains("\"id\":42"), "Replayed body should contain original response: " + bodyStr);
        assertEquals("value1", response.getHeader("X-Custom"), "Replayed headers should include custom header");
        assertTrue(response.getContentType().contains("application/json"), "Content type should be restored: " + response.getContentType());
    }

    /* ------------------------------------------------------------------ */
    /* 4. Invalid key format                                              */
    /* ------------------------------------------------------------------ */

    @Test
    void invalidIdempotencyKey_returns400() throws Exception {
        setAuthenticatedUser("student@test.com");
        ContentCachingRequestWrapper request = newRequest("POST", "/api/students/complaints", null, "bad key with spaces!");
        ContentCachingResponseWrapper response = newResponse();

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        assertEquals(400, response.getStatus());
    }

    /* ------------------------------------------------------------------ */
    /* 5. Key longer than 256 characters                                  */
    /* ------------------------------------------------------------------ */

    @Test
    void idempotencyKeyOver256Chars_returns400() throws Exception {
        setAuthenticatedUser("student@test.com");
        String longKey = "a".repeat(257);
        ContentCachingRequestWrapper request = newRequest("POST", "/api/students/complaints", null, longKey);
        ContentCachingResponseWrapper response = newResponse();

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        assertEquals(400, response.getStatus());
    }

    /* ------------------------------------------------------------------ */
    /* 6. Different users, same key → separate scopes                     */
    /* ------------------------------------------------------------------ */

    @Test
    void sameKeyDifferentUsers_separateScopes() throws Exception {
        String body = "{\"title\":\"broken chair\"}";

        // User A claims the key
        setAuthenticatedUser("student@test.com");
        ContentCachingRequestWrapper requestA = newRequest("POST", "/api/students/complaints", body, "shared-key");
        ContentCachingResponseWrapper responseA = newResponse();
        mockClaim(1L);

        assertTrue(interceptor.preHandle(requestA, responseA, null));
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList("idempotency:student:42:POST:/api/students/complaints:shared-key")),
                any(Object[].class));

        // User B (unauthenticated → "anonymous") with same key - fresh request/response, different scope
        SecurityContextHolder.clearContext();
        reset(redisTemplate, valueOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        mockClaim(1L);
        ContentCachingRequestWrapper requestB = newRequest("POST", "/api/students/complaints", body, "shared-key");
        ContentCachingResponseWrapper responseB = newResponse();

        assertTrue(interceptor.preHandle(requestB, responseB, null));
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList("idempotency:anonymous:POST:/api/students/complaints:shared-key")),
                any(Object[].class));
    }

    /* ------------------------------------------------------------------ */
    /* 7. Same key, different endpoints → separate scopes                 */
    /* ------------------------------------------------------------------ */

    @Test
    void sameKeyDifferentEndpoints_separateScopes() throws Exception {
        setAuthenticatedUser("student@test.com");
        String body = "{\"title\":\"broken chair\"}";

        ContentCachingRequestWrapper request1 = newRequest("POST", "/api/students/complaints", body, "shared-key");
        ContentCachingResponseWrapper response1 = newResponse();
        mockClaim(1L);
        assertTrue(interceptor.preHandle(request1, response1, null));
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList("idempotency:student:42:POST:/api/students/complaints:shared-key")),
                any(Object[].class));

        // Same key on a different endpoint - fresh request/response
        reset(redisTemplate, valueOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        mockClaim(1L);
        ContentCachingRequestWrapper request2 = newRequest("POST", "/api/department/complaints", body, "shared-key");
        ContentCachingResponseWrapper response2 = newResponse();
        assertTrue(interceptor.preHandle(request2, response2, null));
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList("idempotency:student:42:POST:/api/department/complaints:shared-key")),
                any(Object[].class));
    }

    /* ------------------------------------------------------------------ */
    /* 8. Same key, different body → 409                                  */
    /* ------------------------------------------------------------------ */

    @Test
    void duplicateKeyDifferentBody_returns409() throws Exception {
        setAuthenticatedUser("student@test.com");
        ContentCachingRequestWrapper request = newRequest("POST", "/api/students/complaints",
                "{\"title\":\"new complaint\"}", "key-body-diff");
        ContentCachingResponseWrapper response = newResponse();

        String originalHash = sha256Hex("{\"title\":\"old complaint\"}");

        Map<String, Object> completedState = new HashMap<>();
        completedState.put("state", "COMPLETED");
        completedState.put("bodyHash", originalHash);
        completedState.put("owner", "owner-1");
        completedState.put("status", 200);
        completedState.put("body", "{}");

        mockClaim(0L);
        mockGet(objectMapper.writeValueAsString(completedState));

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        assertEquals(409, response.getStatus());
    }

    /* ------------------------------------------------------------------ */
    /* 9. Redis returns null (key absent)                                 */
    /* ------------------------------------------------------------------ */

    @Test
    void redisReturnsNull_treatedAsAbsent_claimSucceeds() throws Exception {
        setAuthenticatedUser("student@test.com");
        ContentCachingRequestWrapper request = newRequest("POST", "/api/students/complaints", null, "key-null-redis");
        ContentCachingResponseWrapper response = newResponse();

        mockClaim(1L);
        mockGet(null);

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result);
        verify(redisTemplate).execute(any(), anyList(), any(Object[].class));
    }

    /* ------------------------------------------------------------------ */
    /* 10. Redis throws exception → fail closed (503), not allow through  */
    /* ------------------------------------------------------------------ */

    @Test
    void redisThrowsException_failClosedReturns503() throws Exception {
        setAuthenticatedUser("student@test.com");
        ContentCachingRequestWrapper request = newRequest("POST", "/api/students/complaints", null, "key-redis-fail");
        ContentCachingResponseWrapper response = newResponse();

        when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("Redis down"));

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        assertEquals(503, response.getStatus());
    }

    /* ------------------------------------------------------------------ */
    /* 11. FAILED state allows retry                                      */
    /* ------------------------------------------------------------------ */

    @Test
    void failedStateAllowsRetry() throws Exception {
        setAuthenticatedUser("student@test.com");
        ContentCachingRequestWrapper request = newRequest("POST", "/api/students/complaints",
                "{\"title\":\"retry me\"}", "key-failed-retry");
        ContentCachingResponseWrapper response = newResponse();

        String bodyHash = sha256Hex("{\"title\":\"retry me\"}");

        Map<String, Object> failedState = new HashMap<>();
        failedState.put("state", "FAILED");
        failedState.put("bodyHash", bodyHash);
        failedState.put("owner", "owner-1");
        failedState.put("createdAt", Instant.now().getEpochSecond());

        mockClaim(0L);
        mockGet(objectMapper.writeValueAsString(failedState));
        // Transition FAILED -> PROCESSING succeeds
        when(redisTemplate.execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result);
    }

    /* ------------------------------------------------------------------ */
    /* 12. PROCESSING state, different owner, not stale → 409             */
    /* ------------------------------------------------------------------ */

    @Test
    void processingStateDifferentOwner_returns409() throws Exception {
        setAuthenticatedUser("student@test.com");
        ContentCachingRequestWrapper request = newRequest("POST", "/api/students/complaints", null,
                "key-processing-concurrent");
        ContentCachingResponseWrapper response = newResponse();

        Map<String, Object> processingState = new HashMap<>();
        processingState.put("state", "PROCESSING");
        processingState.put("bodyHash", "hash");
        processingState.put("owner", "other-owner");
        processingState.put("createdAt", Instant.now().getEpochSecond());

        mockClaim(0L);
        mockGet(objectMapper.writeValueAsString(processingState));
        // Stale reclaim returns 0 (not stale)
        when(redisTemplate.execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        assertEquals(409, response.getStatus());
    }

    /* ------------------------------------------------------------------ */
    /* 13. PROCESSING state, stale + body hash match → atomic reclaim     */
    /* ------------------------------------------------------------------ */

    @Test
    void processingStateStale_bodyHashMatch_atomicReclaimAndProceed() throws Exception {
        setAuthenticatedUser("student@test.com");
        ContentCachingRequestWrapper request = newRequest("POST", "/api/students/complaints", null,
                "key-stale-reclaim");
        ContentCachingResponseWrapper response = newResponse();

        String bodyHash = sha256Hex("{\"title\":\"x\"}");
        long oldTime = Instant.now().getEpochSecond() - 600; // 10 minutes ago
        Map<String, Object> staleState = new HashMap<>();
        staleState.put("state", "PROCESSING");
        staleState.put("bodyHash", bodyHash);
        staleState.put("owner", "other-owner");
        staleState.put("createdAt", oldTime);

        mockClaim(0L);
        mockGet(objectMapper.writeValueAsString(staleState));
        // Stale reclaim returns 1 (reclaimed)
        when(redisTemplate.execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result);
    }

    /* ------------------------------------------------------------------ */
    /* 14. PROCESSING state, stale + body hash MISMATCH → 409             */
    /* ------------------------------------------------------------------ */

    @Test
    void processingStateStale_bodyHashMismatch_returns409() throws Exception {
        setAuthenticatedUser("student@test.com");
        ContentCachingRequestWrapper request = newRequest("POST", "/api/students/complaints",
                "{\"title\":\"different body\"}", "key-stale-mismatch");
        ContentCachingResponseWrapper response = newResponse();

        String storedHash = sha256Hex("{\"title\":\"original body\"}");
        long oldTime = Instant.now().getEpochSecond() - 600;
        Map<String, Object> staleState = new HashMap<>();
        staleState.put("state", "PROCESSING");
        staleState.put("bodyHash", storedHash);
        staleState.put("owner", "other-owner");
        staleState.put("createdAt", oldTime);

        mockClaim(0L);
        mockGet(objectMapper.writeValueAsString(staleState));
        // Stale reclaim returns 3 (stale but body hash mismatch)
        when(redisTemplate.execute(
                any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(3L);

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        assertEquals(409, response.getStatus());
    }

    /* ------------------------------------------------------------------ */
    /* 15. Previous owner cannot overwrite newer owner's state            */
    /* ------------------------------------------------------------------ */

    @Test
    void previousOwnerCannotOverwriteNewerOwner() throws Exception {
        setAuthenticatedUser("student@test.com");
        ContentCachingRequestWrapper request = newRequest("POST", "/api/students/complaints",
                "{\"title\":\"x\"}", "key-owner-overwrite");
        ContentCachingResponseWrapper response = newResponse();

        String bodyHash = sha256Hex("{\"title\":\"x\"}");

        Map<String, Object> state = new HashMap<>();
        state.put("state", "COMPLETED");
        state.put("bodyHash", bodyHash);
        state.put("owner", "newer-owner-token");
        state.put("status", 200);
        state.put("body", "{\"result\":\"original\"}");

        mockClaim(0L);
        mockGet(objectMapper.writeValueAsString(state));

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        // Same key + same body → replay original response regardless of owner
        assertEquals(200, response.getStatus());
        String bodyStr = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(bodyStr.contains("\"result\":\"original\""), "Replayed body should match original: " + bodyStr);
    }

    /* ------------------------------------------------------------------ */
    /* 16. afterCompletion is a no-op — the filter owns the transition     */
    /* ------------------------------------------------------------------ */

    @Test
    void afterCompletion_isNoOp_transitionOwnedByFilter() throws Exception {
        setAuthenticatedUser("student@test.com");
        String scopedKey = "idempotency:student:42:POST:/api/test:key1";
        String ownerToken = "owner-token-1";

        ContentCachingRequestWrapper request = newRequest("POST", "/api/test", null, null);
        request.setAttribute("idempotency.key", scopedKey);
        request.setAttribute("idempotency.owner", ownerToken);

        ContentCachingResponseWrapper response = newResponse();
        response.setStatus(200);
        response.setContentType("application/json");
        response.getWriter().write("{\"id\":42}");

        // afterCompletion must NOT attempt a transition — IdempotencyWrapperFilter
        // owns that, and only it has the ContentCachingResponseWrapper needed
        // to capture the response body for replay.
        assertDoesNotThrow(() -> interceptor.afterCompletion(request, response, null, null));
        verify(redisTemplate, never()).execute(any(), anyList(), any(Object[].class));
    }

    @Test
    void afterCompletion_noIdempotencyKey_noOp() throws Exception {
        ContentCachingRequestWrapper request = newRequest("POST", "/api/test", null, null);
        ContentCachingResponseWrapper response = newResponse();

        assertDoesNotThrow(() -> interceptor.afterCompletion(request, response, null, null));
        verify(redisTemplate, never()).execute(any(), anyList(), any(Object[].class));
    }

    /* ------------------------------------------------------------------ */
    /* 17. Serialization failure throws, never returns fake state         */
    /* ------------------------------------------------------------------ */

    @Test
    void luaScriptsAreConfigured() {
        assertNotNull(interceptor.getClaimScript());
        assertNotNull(interceptor.getStaleReclaimScript());
        assertNotNull(interceptor.getFailedRetryScript());
        assertEquals(Long.class, interceptor.getClaimScript().getResultType());
        assertEquals(Long.class, interceptor.getStaleReclaimScript().getResultType());
        assertEquals(Long.class, interceptor.getFailedRetryScript().getResultType());
    }
}
