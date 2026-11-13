package com.myproject.S2dcms.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/* Caches request/response bodies so IdempotencyInterceptor can hash the
 * request body and replay completed responses. Runs before Spring Security
 * and the MVC interceptor. Multipart requests bypass the wrapper (servlet
 * multipart parsing needs the raw stream). Bodies over 1 MB get HTTP 413.
 * copyBodyToResponse() runs exactly once in the finally block.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdempotencyWrapperFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyWrapperFilter.class);

    private static final String STATE_PROCESSING = "PROCESSING";
    private static final String STATE_COMPLETED = "COMPLETED";
    private static final String STATE_FAILED = "FAILED";

    private static final long MAX_RESPONSE_BODY_BYTES = 64 * 1024;
    private static final long TOTAL_TTL_SECONDS = 86400;

    private static final String TRANSITION_SCRIPT =
            "local existing = redis.call('GET', KEYS[1]) " +
            "if existing == false then return -1 end " +
            "local state = cjson.decode(existing) " +
            "if state.owner ~= ARGV[1] then return 0 end " +
            "if state.state == 'COMPLETED' then return 2 end " +
            "state.state = ARGV[2] " +
            "state.completedAt = tonumber(ARGV[3]) " +
            "if ARGV[4] ~= '' then state.status = tonumber(ARGV[4]) end " +
            "if ARGV[5] ~= '' then state.body = ARGV[5] end " +
            "if ARGV[6] ~= '' then state.headers = ARGV[6] end " +
            "if ARGV[7] ~= '' then state.contentType = ARGV[7] end " +
            "redis.call('SET', KEYS[1], cjson.encode(state), 'EX', ARGV[8]) " +
            "return 1";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DefaultRedisScript<Long> transitionScript;

    public IdempotencyWrapperFilter(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.transitionScript = new DefaultRedisScript<>(TRANSITION_SCRIPT, Long.class);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return !"POST".equalsIgnoreCase(method)
                && !"PUT".equalsIgnoreCase(method)
                && !"PATCH".equalsIgnoreCase(method);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        if (request instanceof ReplayableRequestWrapper) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingResponseWrapper cachedResponse =
                new ContentCachingResponseWrapper(response);
        try {
            if (isMultipart(request)) {
                filterChain.doFilter(request, cachedResponse);
            } else {
                ReplayableRequestWrapper replayableRequest = new ReplayableRequestWrapper(request);
                if (replayableRequest.isOversized()) {
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                    response.getWriter().write(
                            "{\"error\":\"Request entity too large\",\"message\":\"Request body exceeds 5 MB limit\"}");
                    return;
                }
                filterChain.doFilter(replayableRequest, cachedResponse);
            }
        } finally {
            try {
                finalizeIdempotency(request, cachedResponse);
            } catch (Exception e) {
                log.error("Failed to finalize idempotency state", e);
            }
            cachedResponse.copyBodyToResponse();
        }
    }

    private void finalizeIdempotency(HttpServletRequest request, ContentCachingResponseWrapper response) {
        String scopedKey = (String) request.getAttribute("idempotency.key");
        String ownerToken = (String) request.getAttribute("idempotency.owner");
        if (scopedKey == null || ownerToken == null) {
            log.warn("No idempotency attributes found on request for URI {}", request.getRequestURI());
            return;
        }

        try {
            int status = response.getStatus() == 0 ? 200 : response.getStatus();
            log.error("FINALIZING idempotency key {} with status {} owner {}", scopedKey, status, ownerToken.substring(0, Math.min(8, ownerToken.length())));

            boolean isClientError = status >= 400 && status < 500;
            boolean isServerError = status >= 500;

            String newState = (isClientError || isServerError) ? STATE_FAILED : STATE_COMPLETED;

            Map<String, Object> update = new HashMap<>();
            update.put("state", newState);
            update.put("completedAt", Instant.now().getEpochSecond());
            update.put("status", status);

            String contentType = response.getContentType();
            if (contentType != null) {
                update.put("contentType", contentType);
            }

            if (STATE_COMPLETED.equals(newState) && !isMultipart(request)) {
                byte[] bodyBytes = response.getContentAsByteArray();
                if (bodyBytes.length > 0 && bodyBytes.length <= MAX_RESPONSE_BODY_BYTES) {
                    if (isReplayableContentType(contentType)) {
                        update.put("body", new String(bodyBytes, StandardCharsets.UTF_8));
                    }
                }
                Map<String, String> headers = new HashMap<>();
                response.getHeaderNames().forEach(name -> {
                    if ("Content-Type".equalsIgnoreCase(name)
                            || "Content-Language".equalsIgnoreCase(name)
                            || "Cache-Control".equalsIgnoreCase(name)
                            || "ETag".equalsIgnoreCase(name)
                            || "Last-Modified".equalsIgnoreCase(name)
                            || "Vary".equalsIgnoreCase(name)) {
                        headers.put(name, String.join(", ", response.getHeaders(name)));
                    }
                });
                if (!headers.isEmpty()) {
                    update.put("headers", toJson((Map) headers));
                }
            }

            Long result = redisTemplate.execute(
                    transitionScript, Collections.singletonList(scopedKey),
                    ownerToken, newState, String.valueOf(update.get("completedAt")),
                    String.valueOf(update.get("status")),
                    (String) update.getOrDefault("body", ""),
                    (String) update.getOrDefault("headers", ""),
                    (String) update.getOrDefault("contentType", ""),
                    String.valueOf(TOTAL_TTL_SECONDS));
            log.error("TRANSITION RESULT for {} => {}", scopedKey, result);
        } catch (Exception e) {
            log.error("Idempotency Redis failure during finalization for key {}", scopedKey, e);
        }
    }

    private boolean isMultipart(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null
                && contentType.toLowerCase().startsWith("multipart/");
    }

    private boolean isReplayableContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase();
        return type.startsWith("application/json")
                || type.startsWith("text/json")
                || type.startsWith("text/plain")
                || type.startsWith("text/html");
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize headers", e);
        }
    }
}
