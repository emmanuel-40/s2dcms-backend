package com.myproject.S2dcms.interceptor;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;

/**
 * Guards mutating {@code POST / PUT / PATCH} endpoints on {@code /api/**} with an
 * idempotency key. If the same {@code Idempotency-Key} header is submitted twice,
 * the second request is rejected with {@code 409} instead of creating duplicate
 * side effects (a duplicate complaint, a second email, a re-sent push).
 *
 * <p>The key is stored in Redis with a 24h TTL so that retries by a client after
 * a network drop or gateway timeout are safe without leaving stale keys behind.
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    private final RedisTemplate<String, Object> redisTemplate;

    public IdempotencyInterceptor(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String key = request.getHeader("Idempotency-Key");

        // Empty/absent key => no guard. Nothing to dedupe.
        if (key == null || key.isBlank()) {
            return true;
        }

        // Atomic: only the first request for this key wins. setIfAbsent is
        // thread-safe at the Redis level, so concurrent requests with the same
        // key cannot both pass this check.
        Boolean mustNotHaveExisted = redisTemplate.opsForValue()
                .setIfAbsent(key, request.getMethod() + " " + request.getRequestURI(), Duration.ofHours(24));

        if (Boolean.FALSE.equals(mustNotHaveExisted)) {
            response.setStatus(HttpServletResponse.SC_CONFLICT); // 409
            return false;
        }

        return true;
    }
}
