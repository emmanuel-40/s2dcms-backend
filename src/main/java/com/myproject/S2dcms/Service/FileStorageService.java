package com.myproject.S2dcms.Service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

@Service
public class FileStorageService {

    @Value("${file.dir}")
    private String uploadDir;

    @Value("${file.max-size}")
    private DataSize maxFileSize;

    private final SupabaseStorageService supabaseStorage;

    public FileStorageService(SupabaseStorageService supabaseStorage) {
        this.supabaseStorage = supabaseStorage;
    }

    // ---------------- PROFILE (IMAGES ONLY) ----------------
    private static final List<String> IMAGE_TYPES =
            List.of("image/png", "image/jpeg");

    private static final List<String> IMAGE_EXT =
            List.of("png", "jpg", "jpeg");

    // ---------------- ATTACHMENTS (IMAGES + DOCS) ----------------
    private static final List<String> ATTACHMENT_TYPES =
            List.of(
                    "image/png",
                    "image/jpeg",
                    "application/pdf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            );

    private static final List<String> ATTACHMENT_EXT =
            List.of("png", "jpg", "jpeg", "pdf", "doc", "docx");

    // ---------------- COMMON VALIDATION ----------------
    private void validate(MultipartFile file, List<String> types, List<String> extensions) {

        if (file.isEmpty()) {
            throw new RuntimeException("File is empty");
        }

        if (file.getSize() > maxFileSize.toBytes()) {
            throw new RuntimeException("File exceeds " + maxFileSize.toMegabytes() + "MB limit");
        }

        String contentType = file.getContentType();
        String filename = file.getOriginalFilename();

        if (contentType == null || filename == null || !filename.contains(".")) {
            throw new RuntimeException("Invalid file");
        }

        if (!types.contains(contentType)) {
            throw new RuntimeException("File type not allowed");
        }

        String ext = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();

        if (!extensions.contains(ext)) {
            throw new RuntimeException("File extension not allowed");
        }
    }

    // ---------------- PROFILE IMAGE ----------------
    public String storeProfileImage(MultipartFile file) {

        validate(file, IMAGE_TYPES, IMAGE_EXT);

        return save(file, "profile");
    }

    // ---------------- COMPLAINT ATTACHMENT
    public String storeAttachment(MultipartFile file) {

        validate(file, ATTACHMENT_TYPES, ATTACHMENT_EXT);

        return save(file, "attachments");
    }

    // ---------------- SAVE
    private String save(MultipartFile file, String folder) {

        String filename = UUID.randomUUID() + "_" + file.getOriginalFilename();

        // Supabase Storage when configured, local disk otherwise. Both branches return the SAME
        // "/uploads/<folder>/<name>" shape, so existing database rows and frontend URLs keep
        // working untouched - only the bytes move out of the container.
        if (supabaseStorage.isConfigured()) {
            return SupabaseStorageService.UPLOAD_PATH_PREFIX + supabaseStorage.upload(file, folder, filename);
        }

        try {
            Path path = Paths.get(uploadDir, folder)
                    .toAbsolutePath()
                    .normalize()
                    .resolve(filename);

            Files.createDirectories(path.getParent());

            Files.copy(file.getInputStream(), path, StandardCopyOption.REPLACE_EXISTING);

            return "/uploads/" + folder + "/" + filename;

        } catch (Exception e) {
            throw new RuntimeException("Failed to store file", e);
        }
    }

    // ---------------- DELETE FILE
    public void deleteFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return;
        }

        // The stored path is always "/uploads/<folder>/<name>" regardless of which backend wrote it,
        // so deleting must not depend on what happened to be active when the row was written.
        // The bucket is only touched when Supabase is the configured backend.
        if (supabaseStorage.isConfigured()) {
            String relativePath = filePath.startsWith(SupabaseStorageService.UPLOAD_PATH_PREFIX)
                    ? filePath.substring(SupabaseStorageService.UPLOAD_PATH_PREFIX.length())
                    : filePath;

            int slash = relativePath.indexOf('/');
            if (slash > 0 && slash < relativePath.length() - 1) {
                supabaseStorage.delete(relativePath.substring(0, slash),
                        relativePath.substring(slash + 1));
            }
            return;
        }

        try {
            // Extract the relative path from the full URL
            String relativePath = filePath.startsWith("/uploads/") 
                ? filePath.substring("/uploads/".length()) 
                : filePath;

            Path path = Paths.get(uploadDir, relativePath)
                    .toAbsolutePath()
                    .normalize();

            if (Files.exists(path)) {
                Files.delete(path);
            }
        } catch (Exception e) {
            // Log error but don't throw - deletion failure shouldn't break the main operation
            System.err.println("Failed to delete file: " + filePath + ", error: " + e.getMessage());
        }
    }
}