package com.myproject.S2dcms.controller;

import com.myproject.S2dcms.Service.SupabaseStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;

/**
 * Serves every stored upload from a single place, whichever backend wrote it.
 *
 * When Supabase Storage is configured the bytes come from the PRIVATE bucket using the
 * service-role key, which is why the bucket never has to be public and the browser only ever
 * sees the "/uploads/..." URLs it has always used. Without it (local development and tests)
 * the same URL is served from `file.dir`.
 *
 * This deliberately OWNS /uploads/** unconditionally. An earlier version was registered via
 * @ConditionalOnProperty, which matches whenever the property merely EXISTS - including when
 * it resolves to an empty string locally - so it shadowed the static resource handler and every
 * image 404'd in development. Two owners for one path is the bug; one owner is the fix.
 */
@RestController
public class UploadedFileController {

    private final SupabaseStorageService supabaseStorage;

    @Value("${file.dir}")
    private String uploadDir;

    public UploadedFileController(SupabaseStorageService supabaseStorage) {
        this.supabaseStorage = supabaseStorage;
    }

    @GetMapping("/uploads/{folder}/{filename:.+}")
    public ResponseEntity<byte[]> get(@PathVariable String folder, @PathVariable String filename) {

        byte[] bytes = supabaseStorage.isConfigured()
                ? supabaseStorage.download(folder, filename)
                : readLocal(folder, filename);

        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(mediaTypeFor(filename))
                // Filenames are UUID-prefixed and never rewritten, so the bytes at a given path
                // are immutable - safe to cache hard at the browser.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(bytes);
    }

    /** Local-disk fallback for development and tests. Returns null when absent. */
    private byte[] readLocal(String folder, String filename) {
        try {
            Path path = Paths.get(uploadDir, folder).toAbsolutePath().normalize().resolve(filename);
            return Files.exists(path) ? Files.readAllBytes(path) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Derives the content type from the extension rather than trusting anything the client sent,
     * and only for the file types uploads actually accept. Anything unrecognised is served as
     * octet-stream so a browser will download it instead of trying to render it.
     */
    private MediaType mediaTypeFor(String filename) {
        String lower = filename.toLowerCase();

        Map<String, MediaType> types = Map.of(
                "png", MediaType.IMAGE_PNG,
                "jpg", MediaType.IMAGE_JPEG,
                "jpeg", MediaType.IMAGE_JPEG,
                "pdf", MediaType.APPLICATION_PDF,
                "doc", MediaType.parseMediaType("application/msword"),
                "docx", MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        );

        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }

        return types.getOrDefault(lower.substring(dot + 1), MediaType.APPLICATION_OCTET_STREAM);
    }
}