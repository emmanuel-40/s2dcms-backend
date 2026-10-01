package com.myproject.S2dcms.controller;

import com.myproject.S2dcms.Service.SupabaseStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Streams stored uploads back to the browser out of the PRIVATE Supabase bucket.
 *
 * This is what lets the bucket stay private: the browser only ever sees "/uploads/...", the
 * same URLs it has always used, and the service-role key is used exclusively server-side. It
 * only handles requests when Supabase is configured - otherwise WebConfig's static resource
 * handler serves the local disk instead.
 */
@RestController
@ConditionalOnProperty(name = "supabase.storage.url")
public class UploadedFileController {

    private final SupabaseStorageService supabaseStorage;

    public UploadedFileController(SupabaseStorageService supabaseStorage) {
        this.supabaseStorage = supabaseStorage;
    }

    /**
     * Registered ONLY when supabase.storage.url is set, so this controller and WebConfig's
     * static resource handler can never both answer for the same /uploads/** path.
     *
     * Previously the method returned null when Supabase was unconfigured, which Spring turned
     * into a 200 with an empty body - a silently broken image rather than a served file.
     */
    @GetMapping("/uploads/{folder}/{filename:.+}")
    public ResponseEntity<byte[]> get(@PathVariable String folder, @PathVariable String filename) {

        if (!supabaseStorage.isConfigured()) {
            return ResponseEntity.notFound().build();
        }

        byte[] bytes = supabaseStorage.download(folder, filename);
        if (bytes.length == 0) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(mediaTypeFor(filename))
                // Filenames are UUID-prefixed and never rewritten, so the bytes at a given path
                // are immutable - safe to cache hard at the browser.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(bytes);
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