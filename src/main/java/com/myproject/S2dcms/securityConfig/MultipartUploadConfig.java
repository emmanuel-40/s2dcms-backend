package com.myproject.S2dcms.securityConfig;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Owns the servlet-container upload ceiling.
 *
 * Declaring the bean here makes the ceiling travel with the JAR, so it is identical in every
 * environment. It still reads `file.max-size`, so the existing 5MB setting remains the single
 * source of truth and the default below only applies if nothing is configured.
 */
@Configuration
public class MultipartUploadConfig {

    @Value("${file.max-size:${FILE_MAX_SIZE:5MB}}")
    private DataSize maxFileSize;

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(maxFileSize);
        // The request ceiling must be at least the file ceiling, otherwise a single file plus
        // multipart framing overhead trips the request limit before the file limit is reached.
        factory.setMaxRequestSize(maxFileSize);
        return factory.createMultipartConfig();
    }
}