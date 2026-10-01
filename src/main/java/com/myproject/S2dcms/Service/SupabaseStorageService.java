package com.myproject.S2dcms.Service;

import com.myproject.S2dcms.Exception.FileUploadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Stores uploaded files in Supabase Storage instead of the local filesystem.
 *
 * WHY THIS EXISTS: Render's free tier hands out a brand new container on every deploy and on
 * every free-tier cold start. Files written to a relative `file.dir` inside that container are
 * destroyed with it, while the database rows that point at them survive - so profile pictures
 * vanish on each redeploy. Supabase Storage lives outside the container, so uploads persist.
 *
 * The bucket stays PRIVATE. Bytes are fetched back through UploadedFileController rather than
 * by handing the browser a public Storage URL, so the service_role key is never exposed and
 * the stored paths stay exactly the "/uploads/..." values the app has always used - meaning
 * no database migration and no frontend change were needed.
 *
 * Speaks the Storage REST API directly with Spring's RestClient rather than pulling in the
 * Supabase SDK: it is one POST, one GET and one DELETE, so a dependency (and its transitive
 * version conflicts) would not pay for itself. The service-role key is read from configuration
 * and never leaves the server.
 */
@Service
public class SupabaseStorageService {

    private static final Logger logger = LoggerFactory.getLogger(SupabaseStorageService.class);

    /** The stored path prefix the app has always used, e.g. "/uploads/profile/<uuid>_pic.png". */
    public static final String UPLOAD_PATH_PREFIX = "/uploads/";

    /** Returned by download() when the object is missing or the provider is unreachable. */
    static final byte[] EMPTY = new byte[0];

    private final RestClient restClient;

    @Value("${supabase.storage.url:}")
    private String storageUrl;

    @Value("${supabase.storage.service-key:}")
    private String serviceKey;

    @Value("${supabase.storage.bucket:s2dcms-uploads}")
    private String bucket;

    public SupabaseStorageService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    /**
     * True only when both the project URL and the service-role key are present. Callers use this
     * to fall back to local disk, so a half-configured deployment cannot silently break uploads.
     */
    public boolean isConfigured() {
        return StringUtils.hasText(storageUrl) && StringUtils.hasText(serviceKey);
    }

    /**
     * Uploads the file and returns the object KEY ("<folder>/<filename>"). The caller prefixes
     * it with "/uploads/" exactly as before, so existing rows and URLs stay valid.
     */
    public String upload(MultipartFile file, String folder, String filename) {
        try {
            // The filename is passed RAW here on purpose: RestClient already percent-encodes
            // URI template variables, so encoding it first would double-encode ("%20" -> "%2520").
            restClient.post()
                    .uri(storageUrl + "/storage/v1/object/{bucket}/{folder}/{filename}",
                            bucket, folder, safePathSegment(filename))
                    .header("Authorization", "Bearer " + serviceKey)
                    .header("apikey", serviceKey)
                    .contentType(MediaType.parseMediaType(file.getContentType()))
                    .body(file.getBytes())
                    .retrieve()
                    .toBodilessEntity();

            return folder + "/" + safePathSegment(filename);

        } catch (IOException e) {
            throw new FileUploadException("Failed to read the uploaded file");
        } catch (Exception e) {
            // Never surface the provider's raw message: it can echo the bucket name, the key,
            // or an internal project id back to the client.
            logger.error("Supabase Storage upload failed for {}/{}", folder, filename, e);
            throw new FileUploadException("Failed to store the file. Please try again.");
        }
    }

    /**
     * Fetches the bytes of a stored object. Returns {@link #EMPTY} when the object does not
     * exist or the provider is unreachable, so the caller can answer 404 rather than turning a
     * missing file into a 500.
     */
    public byte[] download(String folder, String filename) {
        try {
            byte[] bytes = restClient.get()
                    .uri(storageUrl + "/storage/v1/object/{bucket}/{folder}/{filename}",
                            bucket, folder, safePathSegment(filename))
                    .header("Authorization", "Bearer " + serviceKey)
                    .header("apikey", serviceKey)
                    .retrieve()
                    .body(byte[].class);

            return bytes != null ? bytes : EMPTY;

        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return EMPTY;
            }
            logger.error("Supabase Storage download failed for {}/{}", folder, filename, e);
            return EMPTY;

        } catch (Exception e) {
            logger.error("Supabase Storage download failed for {}/{}", folder, filename, e);
            return EMPTY;
        }
    }

    public void delete(String folder, String filename) {
        try {
            restClient.delete()
                    .uri(storageUrl + "/storage/v1/object/{bucket}/{folder}/{filename}",
                            bucket, folder, safePathSegment(filename))
                    .header("Authorization", "Bearer " + serviceKey)
                    .header("apikey", serviceKey)
                    .retrieve()
                    .toBodilessEntity();

        } catch (Exception e) {
            // Matches the existing local-disk behaviour: a failed delete must not roll back or
            // fail the caller's main operation (e.g. removing a profile picture).
            logger.error("Supabase Storage delete failed for {}/{}", folder, filename, e);
        }
    }

    /**
     * Rejects a filename that could break out of its folder. The stored name is
     * "<uuid>_<originalFilename>", and a browser-supplied name can contain "/" or "\", which
     * would otherwise address a different object (or a different bucket) inside Storage.
     */
    private String safePathSegment(String filename) {
        String safe = filename.replaceAll("[/\\\\]", "_");
        return safe.isBlank() ? "file" : safe;
    }
}