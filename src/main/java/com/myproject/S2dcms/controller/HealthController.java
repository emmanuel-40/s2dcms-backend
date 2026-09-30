package com.myproject.S2dcms.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/health")
public class HealthController {

    private static final Logger logger = LoggerFactory.getLogger(HealthController.class);
    private final RedisTemplate<String, Object> redisTemplate;

    public HealthController(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Health check endpoint for uptime monitoring
     * Returns basic status information to keep the backend awake
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> healthCheck() {
        logger.info("Health check requested at {}", LocalDateTime.now());
        
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", LocalDateTime.now().toString());
        health.put("service", "S2DCMS Backend");
        
        return ResponseEntity.ok(health);
    }

    /**
     * Redis health check endpoint
     * Can be used by UptimeRobot or other monitoring tools
     */
    @GetMapping("/redis")
    public ResponseEntity<Map<String, Object>> redisHealth() {
        try {
            // Check if Redis connection factory is available
            if (redisTemplate.getConnectionFactory() == null) {
                return ResponseEntity.status(503).body(Map.of(
                    "status", "DOWN",
                    "redis", "Connection factory not available",
                    "timestamp", LocalDateTime.now().toString()
                ));
            }
            
            // Ping Redis to check connection
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            return ResponseEntity.ok(Map.of(
                "status", "UP",
                "redis", "Connected",
                "ping", pong,
                "timestamp", LocalDateTime.now().toString()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                "status", "DOWN",
                "redis", "Disconnected",
                "error", e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
            ));
        }
    }
}
