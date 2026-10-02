package com.myproject.S2dcms.securityConfig;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Serialises every LocalDateTime as an explicit UTC instant.
 *
 * NOTE: this deliberately does NOT declare its own ObjectMapper bean. JacksonConfig used to build
 * one by hand, which replaced Spring Boot's auto-configured mapper and silently made this
 * customiser a complete no-op - timestamps stayed an hour wrong even though the code read as though
 * it were handled. Letting Boot own the mapper means customisers, spring.jackson.* properties and
 * module auto-detection all apply. RedisConfig's mapper is a separate bean used for cache
 * serialisation and is deliberately unaffected.
 */
@Configuration
public class JacksonUtcConfig {

    private static final DateTimeFormatter UTC_WITH_ZULU =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer utcDateTimeCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(UTC_WITH_ZULU));

            
            builder.modulesToInstall(module);
        };
    }
}