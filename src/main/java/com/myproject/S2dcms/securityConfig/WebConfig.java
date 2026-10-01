package com.myproject.S2dcms.securityConfig;

import com.myproject.S2dcms.Service.SupabaseStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${file.dir}")
    private String uploadDir;

    private final SupabaseStorageService supabaseStorage;

    public WebConfig(SupabaseStorageService supabaseStorage) {
        this.supabaseStorage = supabaseStorage;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Map /uploads/** to the attachments directory (file.dir property).
        //
        // This static handler only makes sense for the local-disk fallback. When Supabase
        // Storage is configured it is deliberately NOT registered, so requests fall through to
        // UploadedFileController instead - otherwise Spring would serve 404s straight from the
        // empty container directory and the Supabase copy would never be read.
        if (!supabaseStorage.isConfigured()) {
            registry.addResourceHandler("/uploads/**")
                    .addResourceLocations("file:" + uploadDir + "/");
        }
    }
}
