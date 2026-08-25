package com.myproject.S2dcms.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
}
