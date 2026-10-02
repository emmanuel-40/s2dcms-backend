package com.myproject.S2dcms.securityConfig;

import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.unit.DataSize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the upload ceiling that caused 2-3MB uploads to fail in production.
 *
 * The limit used to live only in `application.properties`, which is git-ignored and never
 * deployed, so production silently ran on Spring Boot's 1MB default and rejected anything over
 * 1MB with MaxUploadSizeExceededException - even though FileStorageService's own 5MB check passed
 * it. The two limits disagreed and the smaller one won.
 *
 * This asserts the configured VALUE rather than trying to push bytes through a mocked request:
 * the size is enforced by the servlet container while parsing the stream, which a MockMultipart
 * request (whose parts are already in memory) never exercises. Asserting the ceiling is the part
 * that actually regressed.
 */
@SpringBootTest
class MultipartUploadConfigTest {

    @Autowired
    private MultipartConfigElement multipartConfigElement;

    @Test
    void ceilingIsFiveMegabytesRatherThanTheOneMegabyteDefault() {
        assertNotNull(multipartConfigElement,
                "A MultipartConfigElement bean must exist so the ceiling ships inside the JAR");

        assertEquals(DataSize.ofMegabytes(5).toBytes(), multipartConfigElement.getMaxFileSize(),
                "Upload ceiling must travel with the JAR, not with a git-ignored properties file");
    }

    @Test
    void requestCeilingIsNotSmallerThanTheFileCeiling() {
        assertNotNull(multipartConfigElement);
        assertTrue(multipartConfigElement.getMaxRequestSize() >= multipartConfigElement.getMaxFileSize(),
                "Multipart framing overhead would otherwise trip the request limit before the file limit");
    }
}