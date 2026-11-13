package com.myproject.S2dcms.securityConfig;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.boot.ApplicationRunner;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;

import java.time.Duration;

/**
 * Redis Configuration with connection pooling, serialization, and caching
 */
@Configuration
@EnableCaching
public class RedisConfig {

    private ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.myproject.S2dcms")
                        .allowIfSubType("java.util")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        return mapper;
    }

    @Bean
    public RedisCacheConfiguration cacheConfiguration() {
        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper());

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(serializer)
                );
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfiguration())
                .transactionAware()
                .build();
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper());
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * Flushes all idempotency keys from Redis on application startup.
     * Prevents stale PROCESSING/FAILED state from previous runs
     * from causing "2 uses on first startup" behavior.
     */
    @Bean
    public ApplicationRunner flushIdempotencyKeys(StringRedisTemplate stringRedisTemplate) {
        return args -> {
            try {
                var keys = stringRedisTemplate.keys("idempotency:*");
                if (keys != null && !keys.isEmpty()) {
                    stringRedisTemplate.delete(keys);
                    log().info("Flushed {} stale idempotency keys from Redis on startup", keys.size());
                }
            } catch (Exception e) {
                log().warn("Could not flush idempotency keys on startup", e);
            }
        };
    }

    private static org.slf4j.Logger log() {
        return org.slf4j.LoggerFactory.getLogger(RedisConfig.class);
    }
}
