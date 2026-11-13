package com.myproject.S2dcms.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myproject.S2dcms.Service.UserLookupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyInterceptor.class);

    private static final String REDIS_PREFIX = "idempotency";
    private static final String STATE_PROCESSING = "PROCESSING";
    private static final String STATE_COMPLETED = "COMPLETED";
    private static final String STATE_FAILED = "FAILED";

    private static final long TTL = 86400;
    private static final long STALE = 30;
    private static final int MAX_KEY_LENGTH = 256;

    /* Lua script: Claim an idempotency key atomically using SET IF ABSENT.
     * Returns 1 if the key was claimed (didn't exist), 0 if it already exists.
     * This prevents race conditions where concurrent requests could both
     * claim the same key by performing the check-and-set atomically in Redis.
     */
    private static final String CLAIM_SCRIPT =
            "local existing = redis.call('GET', KEYS[1]) " +
            "if existing == false then " +
            "  redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) " +
            "  return 1 " +
            "end " +
            "return 0";

    /* Lua script: Reclaim a stale PROCESSING key atomically.
     * If a request crashes while processing, its key stays PROCESSING forever.
     * This script lets a new request reclaim it after STALE seconds have passed.
     * Returns 1 if reclaimed, 2 if same owner (already ours), 0 if not stale/wrong state,
     * -1 if key missing, 3 if body hash mismatch (different request body).
     * Also verifies body hash to prevent reclaiming keys for different requests.
     */
    private static final String STALE_RECLAIM_SCRIPT =
            "local existing = redis.call('GET', KEYS[1]) " +
            "if existing == false then return -1 end " +
            "local state = cjson.decode(existing) " +
            "if state.state ~= 'PROCESSING' then return 0 end " +
            "if state.owner == ARGV[1] then return 2 end " +
            "local createdAt = tonumber(state.createdAt or 0) " +
            "local now = tonumber(ARGV[2]) " +
            "local staleAfter = tonumber(ARGV[3]) " +
            "if now - createdAt > staleAfter then " +
            "  if ARGV[6] ~= '' and state.bodyHash ~= ARGV[6] then return 3 end " +
            "  state.owner = ARGV[1] " +
            "  state.state = 'PROCESSING' " +
            "  state.createdAt = tonumber(ARGV[4]) " +
            "  redis.call('SET', KEYS[1], cjson.encode(state), 'EX', ARGV[5]) " +
            "  return 1 " +
            "end " +
            "return 0";

    /* Lua script: Transition a FAILED key back to PROCESSING so it can be retried.
     * A FAILED state means the previous attempt errored out; clients can retry
     * with the same key. Returns 1 if transitioned, 0 if wrong state, -1 if missing.
     */
    private static final String FAILED_RETRY_SCRIPT =
            "local existing = redis.call('GET', KEYS[1]) " +
            "if existing == false then return -1 end " +
            "local state = cjson.decode(existing) " +
            "if state.state ~= 'FAILED' then return 0 end " +
            "state.owner = ARGV[1] " +
            "state.state = 'PROCESSING' " +
            "state.createdAt = tonumber(ARGV[2]) " +
            "redis.call('SET', KEYS[1], cjson.encode(state), 'EX', ARGV[3]) " +
            "return 1";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final UserLookupService userLookupService;

    private final DefaultRedisScript<Long> claimScript;
    private final DefaultRedisScript<Long> staleReclaimScript;
    private final DefaultRedisScript<Long> failedRetryScript;

    public IdempotencyInterceptor(StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  UserLookupService userLookupService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.userLookupService = userLookupService;
        this.claimScript = new DefaultRedisScript<>(CLAIM_SCRIPT, Long.class);
        this.staleReclaimScript = new DefaultRedisScript<>(STALE_RECLAIM_SCRIPT, Long.class);
        this.failedRetryScript = new DefaultRedisScript<>(FAILED_RETRY_SCRIPT, Long.class);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        /* Idempotency flow:
         * 1. Skip safe HTTP methods (GET, HEAD, OPTIONS, TRACE).
         * 2. If no Idempotency-Key header, pass through unchanged.
         * 3. Validate key format (1-256 chars, alphanumeric + .-_:).
         * 4. Scope key by user + method + URI + client-provided key.
         * 5. Atomically claim the key via Redis Lua script (SET IF ABSENT).
         * 6. If key already exists, handle based on state:
         *    - COMPLETED: Return 409 (key already used — strict single-use).
         *    - FAILED: Allow retry by transitioning back to PROCESSING.
         *    - PROCESSING: Check if we own it (same token) or if IdempotencyWrapperFilter
         *      already claimed it (HIGHEST_PRECEDENCE filter runs before interceptor).
         *      If filter's owner matches existing state, allow through. Otherwise 409.
         * 7. On any Redis failure, return 503 Service Unavailable.
         */

        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method) || "TRACE".equalsIgnoreCase(method)) {
            return true;
        }

        String idempotencyKey = request.getHeader("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true;
        }

        if (!isValidKey(idempotencyKey)) {
            return writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid Idempotency-Key: must be 1-256 alphanumeric characters (.-_: allowed)");
        }

        String userId = resolveUserId();
        String scopedKey = buildScopedKey(userId, method, request.getRequestURI(), idempotencyKey);
        String bodyHash = hashRequestBody(request);
        String ownerToken = UUID.randomUUID().toString();

        try {
            Long claimed = redisTemplate.execute(
                    claimScript, Collections.singletonList(scopedKey),
                    toJson(newState(STATE_PROCESSING, bodyHash, ownerToken)), String.valueOf(TTL));

            if (claimed != null && claimed == 1L) {
                request.setAttribute("idempotency.owner", ownerToken);
                request.setAttribute("idempotency.key", scopedKey);
                return true;
            }

            String existingJson = null;
            Object existingObj = redisTemplate.opsForValue().get(scopedKey);
            if (existingObj instanceof String) {
                existingJson = (String) existingObj;
            } else if (existingObj instanceof java.util.Map) {
                try {
                    existingJson = objectMapper.writeValueAsString(existingObj);
                } catch (Exception ex) {
                    existingJson = null;
                }
            } else if (existingObj != null) {
                try {
                    existingJson = objectMapper.writeValueAsString(existingObj);
                } catch (Exception ex) {
                    existingJson = null;
                }
            }
            if (existingJson == null) {
                claimed = redisTemplate.execute(
                        claimScript, Collections.singletonList(scopedKey),
                        toJson(newState(STATE_PROCESSING, bodyHash, ownerToken)), String.valueOf(TTL));
                if (claimed != null && claimed == 1L) {
                    request.setAttribute("idempotency.owner", ownerToken);
                    request.setAttribute("idempotency.key", scopedKey);
                    return true;
                }
                existingJson = (String) redisTemplate.opsForValue().get(scopedKey);
            }

            if (existingJson == null) {
                return writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "Idempotency store unavailable, please retry");
            }

            Map<String, Object> state = parseState(existingJson);
            if (state == null) {
                return writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "Idempotency store unavailable, please retry");
            }

            String stateValue = (String) state.get("state");
            String existingOwner = (String) state.get("owner");
            String existingBodyHash = (String) state.get("bodyHash");

            switch (stateValue) {
                case STATE_COMPLETED:
                    // Strict single-use idempotency: same key + same body → replay original response
                    // Same key + different body → 409 Conflict (possible replay attack)
                    if (existingBodyHash != null && existingBodyHash.equals(bodyHash)) {
                        return replayResponse(response, state);
                    }
                    return writeError(response, HttpServletResponse.SC_CONFLICT,
                            "Idempotency-Key already used with a different request body");

                case STATE_FAILED:
                    Long transitioned = redisTemplate.execute(
                            failedRetryScript, Collections.singletonList(scopedKey),
                            ownerToken, String.valueOf(now()), String.valueOf(TTL));
                    if (transitioned != null && transitioned == 1L) {
                        request.setAttribute("idempotency.owner", ownerToken);
                        request.setAttribute("idempotency.key", scopedKey);
                        return true;
                    }
                    Long reclaimed = redisTemplate.execute(
                            staleReclaimScript, Collections.singletonList(scopedKey),
                            ownerToken, String.valueOf(now()), String.valueOf(STALE), String.valueOf(now()), String.valueOf(TTL), bodyHash);
                    if (reclaimed != null && reclaimed == 1L) {
                        request.setAttribute("idempotency.owner", ownerToken);
                        request.setAttribute("idempotency.key", scopedKey);
                        return true;
                    }
                    if (reclaimed != null && reclaimed == 2L) {
                        request.setAttribute("idempotency.owner", existingOwner);
                        request.setAttribute("idempotency.key", scopedKey);
                        return true;
                    }
                    return writeError(response, HttpServletResponse.SC_CONFLICT,
                            "Request with this Idempotency-Key previously failed — retry with same key");

                case STATE_PROCESSING:
                    // Same request re-entering (retry after filter already set attributes)
                    if (ownerToken.equals(existingOwner)) {
                        request.setAttribute("idempotency.owner", ownerToken);
                        request.setAttribute("idempotency.key", scopedKey);
                        return true;
                    }
                    /* RACE CONDITION FIX:
                     * IdempotencyWrapperFilter runs with HIGHEST_PRECEDENCE (before this interceptor).
                     * The filter may have already claimed this key with its own ownerToken.
                     * When the interceptor runs next, it sees STATE_PROCESSING with the filter's owner.
                     * If the filter's attributes on the request match the existing Redis owner,
                     * we allow the request through instead of returning 409 Conflict.
                     * Without this fix, legitimate requests would get 409 because the filter
                     * and interceptor use different owner tokens for the same key.
                     */
                    String filterOwner = (String) request.getAttribute("idempotency.owner");
                    String filterKey = (String) request.getAttribute("idempotency.key");
                    if (filterOwner != null && filterKey != null && filterOwner.equals(existingOwner)) {
                        request.setAttribute("idempotency.owner", filterOwner);
                        request.setAttribute("idempotency.key", filterKey);
                        return true;
                    }
                    // Different owner actively processing — genuine conflict
                    return writeError(response, HttpServletResponse.SC_CONFLICT,
                            "Request with this Idempotency-Key is already being processed");
            }

        } catch (Exception e) {
            log.warn("Idempotency Redis failure for key {}, rejecting request", scopedKey, e);
            return writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Idempotency store unavailable, please retry");
        }

        return writeError(response, HttpServletResponse.SC_CONFLICT,
                "Idempotency-Key conflict");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) throws Exception {
        /* INTENTIONALLY EMPTY.
         *
         * IdempotencyWrapperFilter (HIGHEST_PRECEDENCE) owns the PROCESSING →
         * COMPLETED/FAILED transition because it is the only component with a
         * ContentCachingResponseWrapper and therefore the only one able to capture
         * the response body for later replay.
         *
         * This interceptor's afterCompletion previously attempted the same
         * transition, but it receives the raw servlet response (not the wrapper),
         * so it could never read the body. Worse, it ran first and marked the key
         * COMPLETED, causing the filter's finalizeIdempotency to bail out with
         * result 2 — which left every stored state body-less and made replays
         * return an empty body. Removing this method fixes that.
         */
    }

    private long now() {
        return Instant.now().getEpochSecond();
    }

    private String resolveUserId() {
        try {
            org.springframework.security.core.Authentication auth =
                    org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && !(auth instanceof AnonymousAuthenticationToken)) {
                Object principal = auth.getPrincipal();
                if (principal instanceof String) {
                    String email = (String) principal;
                    UserLookupService.UserResult user = userLookupService.findByEmail(email);
                    if (user != null) {
                        Object userObj = user.user();
                        if (userObj instanceof com.myproject.S2dcms.model.Student student) {
                            return "student:" + student.getId();
                        }
                        if (userObj instanceof com.myproject.S2dcms.model.Department dept) {
                            return "department:" + dept.getId();
                        }
                    }
                    return "principal:" + email;
                }
                return "principal:" + principal.toString();
            }
        } catch (Exception e) {
            log.warn("Failed to resolve user ID for idempotency scoping", e);
        }
        return "anonymous";
    }

    private String buildScopedKey(String userId, String method, String uri, String idempotencyKey) {
        return REDIS_PREFIX + ":" + userId + ":" + method + ":" + uri + ":" + idempotencyKey;
    }

    private boolean isValidKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            return false;
        }
        return key.matches("^[A-Za-z0-9._:-]{1,256}$");
    }
    private String hashRequestBody(HttpServletRequest request) {
        try {
            byte[] body = readRequestBody(request);
            if (body == null || body.length == 0) {
                return sha256Hex(new byte[0]);
            }
            return sha256Hex(body);
        } catch (Exception e) {
            return sha256Hex(new byte[0]);
        }
    }

    private byte[] readRequestBody(HttpServletRequest request) throws Exception {
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
            String fingerprint = contentType + "|" + request.getContentLengthLong();
            return fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        if (request.getInputStream() != null) {
            return request.getInputStream().readAllBytes();
        }
        return new byte[0];
    }

    private String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
    private Map<String, Object> newState(String stateValue, String bodyHash, String ownerToken) {
        Map<String, Object> state = new HashMap<>();
        state.put("state", stateValue);
        state.put("bodyHash", bodyHash);
        state.put("owner", ownerToken);
        state.put("createdAt", Instant.now().getEpochSecond());
        return state;
    }

    String toJson(Map<String, Object> state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize idempotency state", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseState(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize idempotency state", e);
            return null;
        }
    }

    private boolean replayResponse(HttpServletResponse response, Map<String, Object> state) throws Exception {
        Integer status = (Integer) state.get("status");
        if (status == null) {
            status = 200;
        }
        response.setStatus(status);

        Object contentTypeObj = state.get("contentType");
        if (contentTypeObj instanceof String) {
            response.setContentType((String) contentTypeObj);
        } else {
            response.setContentType("application/json");
        }
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());

        Object headersObj = state.get("headers");
        if (headersObj instanceof String) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> headers = objectMapper.readValue((String) headersObj, Map.class);
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (!"Content-Type".equalsIgnoreCase(entry.getKey())) {
                        response.setHeader(entry.getKey(), entry.getValue());
                    }
                }
            } catch (Exception ignored) {
            }
        }

        String body = (String) state.get("body");
        if (body != null) {
            byte[] bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            response.setContentLength(bodyBytes.length);
            response.getWriter().write(body);
        }
        return false;
    }

    private boolean writeError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        response.getWriter().write(toJson(error));
        return false;
    }

    DefaultRedisScript<Long> getClaimScript() { return claimScript; }
    DefaultRedisScript<Long> getStaleReclaimScript() { return staleReclaimScript; }
    DefaultRedisScript<Long> getFailedRetryScript() { return failedRetryScript; }
}