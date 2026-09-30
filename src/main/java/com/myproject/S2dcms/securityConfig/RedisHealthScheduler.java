package com.myproject.S2dcms.securityConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Redis Health Scheduler
 * Prevents Redis database deletion by keeping connection active
 * Sends periodic PING commands to maintain connection health
 */
@Component
public class RedisHealthScheduler {

    private static final Logger logger = LoggerFactory.getLogger(RedisHealthScheduler.class);
    private final RedisTemplate<String, Object> redisTemplate;
    private int pingCount = 0;

    public RedisHealthScheduler(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Send PING to Redis every 5 minutes to keep connection active
     * This prevents Redis Cloud from marking database as inactive
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void keepRedisActive() {
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            pingCount++;
            logger.info("Redis health check #{} - Status: {}, Time: {}", pingCount, pong, LocalDateTime.now());
            
            // Store a heartbeat key to ensure database activity
            redisTemplate.opsForValue().set("heartbeat:last_ping", LocalDateTime.now().toString());
            
        } catch (Exception e) {
            logger.error("Redis health check failed: {}", e.getMessage());
        }
    }

    /**
     * Log Redis status every hour for monitoring
     */
    @Scheduled(fixedRate = 3600000) // 1 hour
    public void logRedisStatus() {
        try {
            logger.info("=== Redis Status Report ===");
            logger.info("Total PINGs sent: {}", pingCount);
            logger.info("Last heartbeat: {}", redisTemplate.opsForValue().get("heartbeat:last_ping"));
            logger.info("Redis connection: {}", redisTemplate.getConnectionFactory().getConnection().ping());
            logger.info("==========================");
        } catch (Exception e) {
            logger.error("Failed to log Redis status: {}", e.getMessage());
        }
    }
}