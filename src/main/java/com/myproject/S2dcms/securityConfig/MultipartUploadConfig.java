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

    /**
     * Headroom added to the whole-request ceiling above the per-file ceiling. A multipart request
     * carrying a file at the limit also carries the other form fields (title, description) and the
     * multipart framing, so the request is always a little larger than the file itself.
     */
    private static final long REQUEST_HEADROOM_BYTES = 5L * 1024 * 1024;

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(maxFileSize);
        // The per-file ceiling is what the user is told about (file.max-size). The whole-request
        // ceiling must sit ABOVE it by the framing/field overhead, or a file right at the limit
        // trips the request limit with a container-level 413/500 before the clean app-level
        // "File exceeds NMB" check can run. The file ceiling itself is unchanged, so a user still
        // cannot upload anything larger than file.max-size.
        factory.setMaxRequestSize(DataSize.ofBytes(maxFileSize.toBytes() + REQUEST_HEADROOM_BYTES));
        return factory.createMultipartConfig();
    }
}