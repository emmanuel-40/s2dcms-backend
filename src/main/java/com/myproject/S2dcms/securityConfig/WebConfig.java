package com.myproject.S2dcms.securityConfig;

/**
 * /uploads/** is served entirely by controller.UploadedFileController, which reads from
 * Supabase Storage when it is configured and from the local `file.dir` otherwise.
 *
 * The static ResourceHandler that used to live here has been removed on purpose: it mapped
 * /uploads/** onto the local directory, so with Supabase enabled it shadowed the real files
 * and answered every request from a directory that is empty in the container. Keeping two
 * owners for one path is what produced the "image 404s everywhere" behaviour.
 */
public final class WebConfig {

    private WebConfig() {
        // Not a configuration class - retained only as a placeholder so the removal of the
        // /uploads/** static handler is documented in one place.
    }
}
